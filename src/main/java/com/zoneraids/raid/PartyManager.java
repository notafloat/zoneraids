package com.zoneraids.raid;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory squads. Parties only contain online players: members are removed on logout, and
 * invites expire, so the maps stay bounded by the online player count.
 */
public final class PartyManager {
    public static final class Party {
        private UUID leader;
        private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        Party(UUID leader) {
            this.leader = leader;
            members.add(leader);
        }

        public UUID leader() {
            return leader;
        }

        public Set<UUID> members() {
            return members;
        }
    }

    private record Invite(UUID inviter, long expiresAt) {
    }

    private final Map<UUID, Party> byMember = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();

    @Nullable
    public Party get(UUID player) {
        return byMember.get(player);
    }

    /** The player plus their party mates, in party order. */
    public List<UUID> groupOf(UUID player) {
        Party party = byMember.get(player);
        return party == null ? List.of(player) : new ArrayList<>(party.members);
    }

    public boolean isLeaderOrSolo(UUID player) {
        Party party = byMember.get(player);
        return party == null || party.leader.equals(player);
    }

    public void invite(UUID inviter, UUID target, long now, long ttl) {
        invites.put(target, new Invite(inviter, now + ttl));
    }

    /** @return the party joined, or null if there was no valid invite. */
    @Nullable
    public Party accept(UUID player, long now) {
        Invite invite = invites.remove(player);
        if (invite == null || invite.expiresAt < now) {
            return null;
        }
        leave(player);
        Party party = byMember.computeIfAbsent(invite.inviter, Party::new);
        party.members.add(player);
        byMember.put(player, party);
        return party;
    }

    @Nullable
    public UUID pendingInviter(UUID player, long now) {
        Invite invite = invites.get(player);
        return invite != null && invite.expiresAt >= now ? invite.inviter : null;
    }

    /** @return the party the player left, or null if they were not in one. */
    @Nullable
    public Party leave(UUID player) {
        Party party = byMember.remove(player);
        if (party == null) {
            return null;
        }
        party.members.remove(player);
        if (party.members.size() <= 1) {
            party.members.forEach(byMember::remove);
            party.members.clear();
        } else if (party.leader.equals(player)) {
            party.leader = party.members.iterator().next();
        }
        return party;
    }

    public void onLogout(UUID player) {
        leave(player);
        invites.remove(player);
        invites.values().removeIf(i -> i.inviter.equals(player));
    }

    public void tick(long now) {
        Iterator<Invite> it = invites.values().iterator();
        while (it.hasNext()) {
            if (it.next().expiresAt < now) {
                it.remove();
            }
        }
    }

    public void clear() {
        byMember.clear();
        invites.clear();
    }
}
