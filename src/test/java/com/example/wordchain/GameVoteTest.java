package com.example.wordchain;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameVoteTest {
    @Test
    void requiresCeilingOfOneThirdAndExcludesRequester() {
        UUID requester = id(0), a = id(1), b = id(2), c = id(3), d = id(4), e = id(5);
        GameVote vote = new GameVote(GameVote.Action.RESET, requester, "requester", Set.of(requester, a, b, c, d, e));

        assertEquals(GameVote.Action.RESET, vote.action());
        assertEquals(2, vote.requiredVotes());
        assertEquals(GameVote.Result.INELIGIBLE, vote.cast(requester, true));
        assertEquals(GameVote.Result.RECORDED, vote.cast(a, true));
        assertEquals(GameVote.Result.ACCEPTED, vote.cast(b, true));
    }

    @Test
    void stopProposalUsesSameQuorumAndEachPlayerCanVoteOnlyOnce() {
        UUID requester = id(10), a = id(11), b = id(12), c = id(13), d = id(14), e = id(15);
        GameVote vote = new GameVote(GameVote.Action.STOP, requester, "requester", Set.of(requester, a, b, c, d, e));

        assertEquals(GameVote.Action.STOP, vote.action());
        assertEquals(GameVote.Result.RECORDED, vote.cast(a, false));
        assertEquals(GameVote.Result.DUPLICATE, vote.cast(a, true));
        assertEquals(GameVote.Result.REJECTED, vote.cast(b, false));
        assertEquals(2, vote.noVotes());
    }

    @Test
    void threeOnlinePlayersNeedOneVoteFromTheOtherTwo() {
        UUID requester = id(20), a = id(21), b = id(22);
        GameVote vote = new GameVote(GameVote.Action.STOP, requester, "requester", Set.of(requester, a, b));

        assertEquals(1, vote.requiredVotes());
        assertEquals(GameVote.Result.ACCEPTED, vote.cast(a, true));
    }

    private static UUID id(long suffix) { return new UUID(0, suffix); }
}
