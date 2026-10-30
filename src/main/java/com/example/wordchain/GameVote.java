package com.example.wordchain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One public yes/no proposal. Eligible voters are snapshotted when voting starts. */
final class GameVote {
    enum Action { RESET, STOP }
    enum Result { ACCEPTED, REJECTED, RECORDED, DUPLICATE, INELIGIBLE }

    private final Action action;
    private final UUID requester;
    private final String requesterName;
    private final Set<UUID> eligibleVoters;
    private final Map<UUID, Boolean> votes = new HashMap<>();
    private final int requiredVotes;

    GameVote(Action action, UUID requester, String requesterName, Set<UUID> onlinePlayers) {
        this.action = action;
        this.requester = requester;
        this.requesterName = requesterName;
        this.eligibleVoters = new HashSet<>(onlinePlayers);
        this.eligibleVoters.remove(requester);
        this.requiredVotes = Math.max(1, (onlinePlayers.size() + 2) / 3);
    }

    Result cast(UUID voter, boolean accept) {
        if (!eligibleVoters.contains(voter)) return Result.INELIGIBLE;
        if (votes.containsKey(voter)) return Result.DUPLICATE;
        votes.put(voter, accept);
        int count = count(accept);
        if (count >= requiredVotes) return accept ? Result.ACCEPTED : Result.REJECTED;
        return Result.RECORDED;
    }

    private int count(boolean accepted) {
        return (int) votes.values().stream().filter(vote -> vote == accepted).count();
    }

    Action action() { return action; }
    UUID requester() { return requester; }
    String requesterName() { return requesterName; }
    int yesVotes() { return count(true); }
    int noVotes() { return count(false); }
    int requiredVotes() { return requiredVotes; }
}
