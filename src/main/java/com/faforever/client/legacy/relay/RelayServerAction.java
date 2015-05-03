package com.faforever.client.legacy.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;

public enum RelayServerAction {
  PROCESS_NAT_PACKET("ProcessNatPacket"),
  DISCONNECTED("Disconnected"),
  CONNECTED("Connected"),
  GAME_STATE("GameState"),
  BOTTLENECK("Bottleneck"),
  BOTTLENECK_CLEARED("BottleneckCleared"),
  GAME_OPTION("GameOption"),
  GAME_MODS("GameMods"),
  PLAYER_OPTION("PlayerOption"),
  PONG("pong"),
  UNKNOWN(null);

  private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final Map<String, RelayServerAction> fromString;
  static {
    fromString = new HashMap<>();
    for (RelayServerAction action : values()) {
      fromString.put(action.string, action);
    }
  }

  public static RelayServerAction fromString(String string) {
    RelayServerAction action = fromString.get(string);
    if (action == null) {
      logger.warn("Unknown relay server action: {}", string);
      return UNKNOWN;
    }
    return action;
  }

  private String string;

  RelayServerAction(String string) {
    this.string = string;
  }

  public String getString() {
    return string;
  }

}
