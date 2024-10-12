package com.astoncss;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.StringJoiner;
import java.util.UUID;

enum PlayerConnection {
    Online, Floodgate
}

class ACSSPlayer {
    public final PlayerConnection con;
    public final UUID uuid;
    public final String playerName;

    ACSSPlayer(PlayerConnection con, UUID uuid, String playerName) {
        this.con = con;
        this.uuid = uuid;
        this.playerName = playerName;
    }
}

class AccountPair {
    public ACSSPlayer online;
    public ACSSPlayer floodgate;

    AccountPair(ACSSPlayer online, ACSSPlayer floodgate) {
        this.online = online;
        this.floodgate = floodgate;
    }

}

public class MemberMap {
    private HashMap<UUID, Integer> uuidMap = new HashMap<>();
    private HashMap<ACSSMember, AccountPair> memberMap = new HashMap<>();

    public int addPlayer(ACSSMember member, UUID player, String playerName, PlayerConnection con) {
        uuidMap.put(player, member.studentID);
        AccountPair accounts = memberMap.get(member);
        if (accounts != null) {
            if (con == PlayerConnection.Online) {
                if (accounts.online == null) {
                    accounts.online = new ACSSPlayer(con, player, playerName);
                } else {
                    ACSSServerCompanion.LOGGER.error("{} already has a java account registered.", member.studentID);
                    return -1;
                }
            } else if (con == PlayerConnection.Floodgate) {
                if (accounts.floodgate == null) {
                    accounts.floodgate = new ACSSPlayer(con, player, playerName);
                } else {
                    ACSSServerCompanion.LOGGER.error("{} already has a bedrock account registered.", member.studentID);
                    return -1;
                }
            }
        } else {
            if (con == PlayerConnection.Online) {
                memberMap.put(member, new AccountPair(new ACSSPlayer(con, player, playerName), null));
            } else if (con == PlayerConnection.Floodgate) {
                memberMap.put(member, new AccountPair(null, new ACSSPlayer(con, player, playerName)));
            }
        }
        ACSSServerCompanion.LOGGER.info("Registered member {} ({})", member.studentID, playerName);
        return 0;
    }

    public void removeMember(ACSSMember member) {
        AccountPair accounts = memberMap.remove(member);
        if (accounts == null) {
            ACSSServerCompanion.LOGGER.warn("Tried to remove unregistered member {}", member.studentID);
        } else {
            if (accounts.floodgate != null) {
                uuidMap.remove(accounts.floodgate.uuid);
                ACSSServerCompanion.LOGGER.info("Removed member {} (Geyser player: {})", member.studentID, accounts.floodgate.playerName);
            }
            if (accounts.online != null) {
                uuidMap.remove(accounts.online.uuid);
                ACSSServerCompanion.LOGGER.info("Removed member {} (Java player: {})", member.studentID, accounts.online.playerName);
            }
        }
    }

    public AccountPair getAccount(ACSSMember member) {
        return memberMap.get(member);
    }

    public Integer getStudentID(UUID player) {
        return uuidMap.get(player);
    }

    public boolean isRegistered(UUID player) {
        return uuidMap.containsKey(player);
    }

    public void writeMap(File f) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(f, false))) {
            for (ACSSMember m : memberMap.keySet()) {
                AccountPair accounts = memberMap.get(m);
                StringJoiner s = new StringJoiner(" ");
                s.add(String.valueOf(m.studentID));
                s.add(String.valueOf(m.yearVerified));

                if (accounts.online != null) {
                    s.add("JAVA");
                    s.add(accounts.online.uuid.toString());
                    s.add(accounts.online.playerName);
                }

                if (accounts.floodgate != null) {
                    s.add("BEDROCK");
                    s.add(accounts.floodgate.uuid.toString());
                    s.add(accounts.floodgate.playerName);
                }

                writer.write(s.toString());
                writer.newLine();
            }
        } catch (IOException e) {
            ACSSServerCompanion.LOGGER.error("Failed to write acss whitelist: {}", e);
        }
    }
}
