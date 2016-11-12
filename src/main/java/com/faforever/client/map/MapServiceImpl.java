package com.faforever.client.map;

import com.faforever.client.config.CacheNames;
import com.faforever.client.i18n.I18n;
import com.faforever.client.map.MapBean.Type;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.remote.AssetService;
import com.faforever.client.remote.FafService;
import com.faforever.client.task.CompletableTask;
import com.faforever.client.task.CompletableTask.Priority;
import com.faforever.client.task.TaskService;
import com.faforever.client.theme.ThemeService;
import com.faforever.client.util.ProgrammingError;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.image.Image;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.Directory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.faforever.client.util.LuaUtil.loadFile;
import static com.github.nocatch.NoCatch.noCatch;
import static com.google.common.net.UrlEscapers.urlFragmentEscaper;
import static java.lang.String.format;
import static java.nio.file.Files.list;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.util.stream.Collectors.toCollection;

public class MapServiceImpl implements MapService {

  private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final float MAP_SIZE_FACTOR = 51.2f;
  private static final Lock LOOKUP_LOCK = new ReentrantLock();

  @Resource
  PreferencesService preferencesService;
  @Resource
  TaskService taskService;
  @Resource
  ApplicationContext applicationContext;
  @Resource
  Directory directory;
  @Resource
  Analyzer analyzer;
  @Resource
  ThreadPoolExecutor threadPoolExecutor;
  @Resource
  FafService fafService;
  @Resource
  AssetService assetService;

  @Value("${vault.mapDownloadUrl}")
  String mapDownloadUrl;
  @Value("${vault.mapPreviewUrlFormat}")
  String mapPreviewUrlFormat;
  @Resource
  I18n i18n;
  @Resource
  ThemeService themeService;

  private Map<Path, MapBean> pathToMap;
  private AnalyzingInfixSuggester suggester;
  private Path mapsDirectory;
  private ObservableList<MapBean> installedSkirmishMaps;
  private Map<String, MapBean> mapsByFolderName;

  public MapServiceImpl() {
    pathToMap = new HashMap<>();
    installedSkirmishMaps = FXCollections.observableArrayList();
    mapsByFolderName = new HashMap<>();

    installedSkirmishMaps.addListener((ListChangeListener<MapBean>) change -> {
      while (change.next()) {
        for (MapBean mapBean : change.getRemoved()) {
          mapsByFolderName.remove(mapBean.getFolderName().toLowerCase());
        }
        for (MapBean mapBean : change.getAddedSubList()) {
          mapsByFolderName.put(mapBean.getFolderName().toLowerCase(), mapBean);
        }
      }
    });
  }

  private static URL getDownloadUrl(String mapName, String baseUrl) {
    return noCatch(() -> new URL(format(baseUrl, urlFragmentEscaper().escape(mapName).toLowerCase(Locale.US))));
  }

  private static URL getPreviewUrl(String mapName, String baseUrl, PreviewSize previewSize) {
    return noCatch(() -> new URL(format(baseUrl, previewSize.folderName, urlFragmentEscaper().escape(mapName).toLowerCase(Locale.US))));
  }

  @PostConstruct
  void postConstruct() throws IOException {
    mapsDirectory = preferencesService.getPreferences().getForgedAlliance().getCustomMapsDirectory();
    preferencesService.getPreferences().getForgedAlliance().customMapsDirectoryProperty().addListener((observable, oldValue, newValue) -> {
      if (newValue != null) {
        onMapDirectoryReady();
      }
    });

    if (mapsDirectory != null) {
      onMapDirectoryReady();
    }

    suggester = new AnalyzingInfixSuggester(directory, analyzer);
  }

  private void onMapDirectoryReady() {
    try {
      Files.createDirectories(mapsDirectory);
      startDirectoryWatcher(mapsDirectory);
    } catch (IOException | InterruptedException e) {
      logger.warn("Could not start map directory watcher", e);
      // TODO notify user
    }
    loadInstalledMaps();
  }

  private void startDirectoryWatcher(Path mapsDirectory) throws IOException, InterruptedException {
    threadPoolExecutor.execute(() -> noCatch(() -> {
      WatchService watcher = mapsDirectory.getFileSystem().newWatchService();
      MapServiceImpl.this.mapsDirectory.register(watcher, ENTRY_DELETE);

      while (!Thread.interrupted()) {
        WatchKey key = watcher.take();
        key.pollEvents().stream()
            .filter(event -> event.kind() == ENTRY_DELETE)
            .forEach(event -> removeMap(mapsDirectory.resolve((Path) event.context())));
        key.reset();
      }
    }));
  }

  private void loadInstalledMaps() {
    taskService.submitTask(new CompletableTask<Void>(Priority.LOW) {
      @Override
      protected Void call() throws Exception {
        updateTitle(i18n.get("mapVault.loadingMaps"));
        Path officialMapsPath = preferencesService.getPreferences().getForgedAlliance().getPath().resolve("maps");

        try {
          List<Path> mapPaths = new ArrayList<>();
          Files.list(mapsDirectory).collect(toCollection(() -> mapPaths));
          Arrays.stream(OfficialMap.values())
              .map(map -> officialMapsPath.resolve(map.name()))
              .collect(toCollection(() -> mapPaths));

          long totalMaps = mapPaths.size();
          long mapsRead = 0;
          for (Path mapPath : mapPaths) {
            updateProgress(++mapsRead, totalMaps);
            addSkirmishMap(mapPath);
          }
        } catch (IOException e) {
          logger.warn("Maps could not be read from: " + mapsDirectory, e);
        }
        return null;
      }
    });
  }

  private void removeMap(Path path) {
    installedSkirmishMaps.remove(pathToMap.remove(path));
  }

  private void addSkirmishMap(Path path) throws MapLoadException {
    try {
      MapBean mapBean = readMap(path);
      pathToMap.put(path, mapBean);
      if (!installedSkirmishMaps.contains(mapBean) && mapBean.getType() == Type.SKIRMISH) {
        installedSkirmishMaps.add(mapBean);
      }
    } catch (MapLoadException e) {
      logger.warn("Map could not be read: " + path.getFileName(), e);
    }
  }

  @Override
  @NotNull
  public MapBean readMap(Path mapFolder) throws MapLoadException {
    if (!Files.isDirectory(mapFolder)) {
      throw new MapLoadException("Not a folder: " + mapFolder.toAbsolutePath());
    }

    Path scenarioLuaPath = noCatch(() -> list(mapFolder))
        .filter(file -> file.getFileName().toString().endsWith("_scenario.lua"))
        .findFirst()
        .orElseThrow(() -> new MapLoadException("Map folder does not contain a *_scenario.lua: " + mapFolder.toAbsolutePath()));

    try {
      LuaValue luaRoot = noCatch(() -> loadFile(scenarioLuaPath), MapLoadException.class);
      LuaValue scenarioInfo = luaRoot.get("ScenarioInfo");
      LuaValue size = scenarioInfo.get("size");

      MapBean mapBean = new MapBean();
      mapBean.setFolderName(mapFolder.getFileName().toString());
      mapBean.setDisplayName(scenarioInfo.get("name").toString());
      mapBean.setDescription(scenarioInfo.get("description").tojstring().replaceAll("<LOC .*?>", ""));
      mapBean.setVersion(scenarioInfo.get("map_version").toint());
      mapBean.setType(Type.fromString(scenarioInfo.get("type").toString()));
      mapBean.setSize(new MapSize(
          (int) (size.get(1).toint() / MAP_SIZE_FACTOR),
          (int) (size.get(2).toint() / MAP_SIZE_FACTOR))
      );
      mapBean.setPlayers(scenarioInfo.get("Configurations").get("standard").get("teams").get(1).get("armies").length());
      return mapBean;
    } catch (LuaError e) {
      throw new MapLoadException(e);
    }
  }

  @Override
  @Cacheable(value = CacheNames.MAP_PREVIEW, unless = "#result == null")
  public Image loadPreview(String mapName, PreviewSize previewSize) {
    return loadPreview(getPreviewUrl(mapName, mapPreviewUrlFormat, previewSize), previewSize);
  }

  @Override
  public ObservableList<MapBean> getInstalledMaps() {
    return installedSkirmishMaps;
  }

  @Override
  public MapBean getMapBeanLocallyFromName(String mapName) {
    logger.debug("Trying to return {} mapInfoBean locally", mapName);
    for (MapBean mapBean : getInstalledMaps()) {
      if (mapName.equalsIgnoreCase(mapBean.getDisplayName())) {
        logger.debug("Found map {} locally", mapName);
        return mapBean;
      }
    }
    return null;
  }

  @Override
  public MapBean findMapByName(String mapName) {
    return fafService.findMapByName(mapName);
  }

  @Override
  public boolean isOfficialMap(String mapName) {
    return OfficialMap.fromMapName(mapName) != null;
  }

  @Override
  public boolean isInstalled(String mapFolderName) {
    return mapsByFolderName.containsKey(mapFolderName.toLowerCase());
  }

  @Override
  public CompletionStage<Void> download(String technicalMapName) {
    URL mapUrl = getDownloadUrl(technicalMapName, mapDownloadUrl);
    return downloadAndInstallMap(technicalMapName, mapUrl, null, null);
  }

  @Override
  public CompletionStage<Void> downloadAndInstallMap(MapBean map, @Nullable DoubleProperty progressProperty, @Nullable StringProperty titleProperty) {
    return downloadAndInstallMap(map.getFolderName(), map.getDownloadUrl(), progressProperty, titleProperty);
  }

  @Override
  public CompletionStage<List<MapBean>> lookupMap(String string, int maxResults) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        LOOKUP_LOCK.lock();
        MapInfoBeanIterator iterator = new MapInfoBeanIterator(fafService.getMaps().iterator());
        suggester.build(iterator);
        return suggester.lookup(string, maxResults, true, false).stream()
            .map(lookupResult -> iterator.deserialize(lookupResult.payload.bytes))
            .collect(Collectors.toList());
      } catch (IOException e) {
        throw new RuntimeException(e);
      } finally {
        LOOKUP_LOCK.unlock();
      }
    }, threadPoolExecutor).exceptionally(throwable -> {
      logger.warn("Lookup failed", throwable);
      return null;
    });
  }

  @Override
  public CompletionStage<List<MapBean>> getMostDownloadedMaps(int count) {
    return fafService.getMostDownloadedMaps(count);
  }

  @Override
  public CompletionStage<List<MapBean>> getMostLikedMaps(int count) {
    return fafService.getMostLikedMaps(count);
  }

  @Override
  public CompletionStage<List<MapBean>> getNewestMaps(int count) {
    return fafService.getNewestMaps(count);
  }

  @Override
  public CompletionStage<List<MapBean>> getMostPlayedMaps(int count) {
    return fafService.getMostPlayedMaps(count);
  }

  @Override
  @Cacheable(CacheNames.MAP_PREVIEW)
  public Image loadPreview(MapBean map, PreviewSize previewSize) {
    URL url;
    switch (previewSize) {
      case SMALL:
        url = map.getSmallThumbnailUrl();
        break;
      case LARGE:
        url = map.getLargeThumbnailUrl();
        break;
      default:
        throw new ProgrammingError("Uncovered preview size: " + previewSize);
    }
    return loadPreview(url, previewSize);
  }

  private Image loadPreview(URL url, PreviewSize previewSize) {
    return assetService.loadAndCacheImage(url, Paths.get("maps").resolve(previewSize.folderName), themeService.getThemeFileUrl(ThemeService.UNKNOWN_MAP_IMAGE));
  }

  @Override
  public CompletionStage<Void> uninstallMap(MapBean map) {
    UninstallMapTask task = applicationContext.getBean(com.faforever.client.map.UninstallMapTask.class);
    task.setMap(map);
    return taskService.submitTask(task).getFuture();
  }

  @Override
  public Path getPathForMap(MapBean map) {
    return getPathForMap(map.getFolderName());
  }

  @Override
  public Path getPathForMap(String technicalName) {
    Path path = preferencesService.getPreferences().getForgedAlliance().getCustomMapsDirectory().resolve(technicalName);
    if (Files.notExists(path)) {
      return null;
    }
    return path;
  }

  @Override
  public CompletableTask<Void> uploadMap(Path mapPath, boolean ranked) {
    MapUploadTask mapUploadTask = applicationContext.getBean(MapUploadTask.class);
    mapUploadTask.setMapPath(mapPath);
    mapUploadTask.setRanked(ranked);

    return taskService.submitTask(mapUploadTask);
  }

  @Override
  @CacheEvict(CacheNames.MAPS)
  public void evictCache() {
    // Nothing to see here
  }

  private CompletionStage<Void> downloadAndInstallMap(String folderName, URL downloadUrl, @Nullable DoubleProperty progressProperty, @Nullable StringProperty titleProperty) {
    DownloadMapTask task = applicationContext.getBean(DownloadMapTask.class);
    task.setMapUrl(downloadUrl);
    task.setFolderName(folderName);

    if (progressProperty != null) {
      progressProperty.bind(task.progressProperty());
    }
    if (titleProperty != null) {
      titleProperty.bind(task.titleProperty());
    }

    return taskService.submitTask(task).getFuture()
        .thenAccept(aVoid -> noCatch(() -> addSkirmishMap(getPathForMap(folderName))));
  }

  public enum OfficialMap {
    SCMP_001, SCMP_002, SCMP_003, SCMP_004, SCMP_005, SCMP_006, SCMP_007, SCMP_008, SCMP_009, SCMP_010, SCMP_011,
    SCMP_012, SCMP_013, SCMP_014, SCMP_015, SCMP_016, SCMP_017, SCMP_018, SCMP_019, SCMP_020, SCMP_021, SCMP_022,
    SCMP_023, SCMP_024, SCMP_025, SCMP_026, SCMP_027, SCMP_028, SCMP_029, SCMP_030, SCMP_031, SCMP_032, SCMP_033,
    SCMP_034, SCMP_035, SCMP_036, SCMP_037, SCMP_038, SCMP_039, SCMP_040, X1MP_001, X1MP_002, X1MP_003, X1MP_004,
    X1MP_005, X1MP_006, X1MP_007, X1MP_008, X1MP_009, X1MP_010, X1MP_011, X1MP_012, X1MP_014, X1MP_017;

    private static final Map<String, OfficialMap> fromString;

    static {
      fromString = new HashMap<>();
      for (OfficialMap officialMap : values()) {
        fromString.put(officialMap.name(), officialMap);
      }
    }

    public static OfficialMap fromMapName(String mapName) {
      return fromString.get(mapName.toUpperCase());
    }
  }

  public enum PreviewSize {
    // These must match the preview URLs
    SMALL("small"), LARGE("large");

    String folderName;

    PreviewSize(String folderName) {
      this.folderName = folderName;
    }
  }
}
