package org.axonframework.eventhandling.pooled;

import org.axonframework.common.transaction.NoTransactionManager;
import org.axonframework.eventhandling.GlobalSequenceTrackingToken;
import org.axonframework.eventhandling.MergedTrackingToken;
import org.axonframework.eventhandling.Segment;
import org.axonframework.eventhandling.TrackingToken;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.UnableToClaimTokenException;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link MergeTask}.
 *
 * @author Steven van Beelen
 */
class MergeTaskTest {

    private static final String PROCESSOR_NAME = "test";
    private static final int SEGMENT_TO_MERGE = 0;
    private static final int SEGMENT_TO_BE_MERGED = 1;
    private static final int[] SEGMENT_IDS = {0, 1};
    private static final Segment SEGMENT_ZERO = Segment.computeSegment(SEGMENT_TO_MERGE, SEGMENT_IDS);
    private static final Segment SEGMENT_ONE = Segment.computeSegment(SEGMENT_TO_BE_MERGED, SEGMENT_IDS);

    private CompletableFuture<Boolean> result;
    private final Map<Integer, WorkPackage> workPackages = new HashMap<>();
    private final TokenStore tokenStore = mock(TokenStore.class);

    private MergeTask testSubject;

    private final WorkPackage workPackageOne = mock(WorkPackage.class);
    private final WorkPackage workPackageTwo = mock(WorkPackage.class);

    @BeforeEach
    void setUp() {
        result = new CompletableFuture<>();
        when(tokenStore.fetchSegments(PROCESSOR_NAME)).thenReturn(SEGMENT_IDS);

        testSubject = new MergeTask(
                result, PROCESSOR_NAME, SEGMENT_TO_MERGE, workPackages, tokenStore, NoTransactionManager.instance()
        );
    }

    @Test
    void testRunReturnsFalseThroughSegmentIdsWhichCannotMerge() throws ExecutionException, InterruptedException {
        when(tokenStore.fetchSegments(PROCESSOR_NAME)).thenReturn(new int[]{SEGMENT_TO_MERGE});

        testSubject.run();

        verify(tokenStore).fetchSegments(PROCESSOR_NAME);
        assertTrue(result.isDone());
        assertFalse(result.get());
    }

    @Test
    void testRunMergeSegmentsFromWorkPackages() throws ExecutionException, InterruptedException {
        TrackingToken testTokenToMerge = new GlobalSequenceTrackingToken(0);
        TrackingToken testTokenToBeMerged = new GlobalSequenceTrackingToken(1);

        when(workPackageOne.segment()).thenReturn(SEGMENT_ZERO);
        when(workPackageOne.stopPackage()).thenReturn(CompletableFuture.completedFuture(testTokenToMerge));
        workPackages.put(SEGMENT_TO_MERGE, workPackageOne);
        when(workPackageTwo.segment()).thenReturn(SEGMENT_ONE);
        when(workPackageTwo.stopPackage()).thenReturn(CompletableFuture.completedFuture(testTokenToBeMerged));
        workPackages.put(SEGMENT_TO_BE_MERGED, workPackageTwo);

        ArgumentCaptor<TrackingToken> mergedTokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);

        testSubject.run();

        verify(tokenStore).fetchSegments(PROCESSOR_NAME);
        verify(tokenStore).deleteToken(PROCESSOR_NAME, SEGMENT_TO_BE_MERGED);
        verify(tokenStore).storeToken(mergedTokenCaptor.capture(), eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE));
        TrackingToken resultToken = mergedTokenCaptor.getValue();
        assertTrue(resultToken.getClass().isAssignableFrom(MergedTrackingToken.class));
        assertEquals(testTokenToMerge, ((MergedTrackingToken) resultToken).lowerSegmentToken());
        assertEquals(testTokenToBeMerged, ((MergedTrackingToken) resultToken).upperSegmentToken());
        verify(tokenStore).releaseClaim(PROCESSOR_NAME, SEGMENT_TO_MERGE);

        assertTrue(result.isDone());
        assertTrue(result.get());
    }

    @Test
    void testRunMergeSegmentsAfterClaimingBoth() throws ExecutionException, InterruptedException {
        TrackingToken testTokenToMerge = new GlobalSequenceTrackingToken(0);
        TrackingToken testTokenToBeMerged = new GlobalSequenceTrackingToken(1);
        when(tokenStore.fetchToken(PROCESSOR_NAME, SEGMENT_TO_MERGE)).thenReturn(testTokenToMerge);
        when(tokenStore.fetchToken(PROCESSOR_NAME, SEGMENT_TO_BE_MERGED)).thenReturn(testTokenToBeMerged);

        ArgumentCaptor<TrackingToken> mergedTokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);

        testSubject.run();

        verify(tokenStore).fetchSegments(PROCESSOR_NAME);
        verify(tokenStore).deleteToken(PROCESSOR_NAME, SEGMENT_TO_BE_MERGED);
        verify(tokenStore).storeToken(mergedTokenCaptor.capture(), eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE));
        TrackingToken resultToken = mergedTokenCaptor.getValue();
        assertTrue(resultToken.getClass().isAssignableFrom(MergedTrackingToken.class));
        assertEquals(testTokenToMerge, ((MergedTrackingToken) resultToken).lowerSegmentToken());
        assertEquals(testTokenToBeMerged, ((MergedTrackingToken) resultToken).upperSegmentToken());
        verify(tokenStore).releaseClaim(PROCESSOR_NAME, SEGMENT_TO_MERGE);

        assertTrue(result.isDone());
        assertTrue(result.get());
    }

    @Test
    void testRunMergeSegmentsFromWorkPackageAndClaimedSegment() throws ExecutionException, InterruptedException {
        TrackingToken testTokenToMerge = new GlobalSequenceTrackingToken(0);
        TrackingToken testTokenToBeMerged = new GlobalSequenceTrackingToken(1);

        when(workPackageOne.segment()).thenReturn(SEGMENT_ZERO);
        when(workPackageOne.stopPackage()).thenReturn(CompletableFuture.completedFuture(testTokenToMerge));
        workPackages.put(SEGMENT_TO_MERGE, workPackageOne);
        when(tokenStore.fetchToken(PROCESSOR_NAME, SEGMENT_TO_BE_MERGED)).thenReturn(testTokenToBeMerged);

        ArgumentCaptor<TrackingToken> mergedTokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);

        testSubject.run();

        verify(tokenStore).fetchSegments(PROCESSOR_NAME);
        verify(tokenStore).deleteToken(PROCESSOR_NAME, SEGMENT_TO_BE_MERGED);
        verify(tokenStore).storeToken(mergedTokenCaptor.capture(), eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE));
        TrackingToken resultToken = mergedTokenCaptor.getValue();
        assertTrue(resultToken.getClass().isAssignableFrom(MergedTrackingToken.class));
        assertEquals(testTokenToMerge, ((MergedTrackingToken) resultToken).lowerSegmentToken());
        assertEquals(testTokenToBeMerged, ((MergedTrackingToken) resultToken).upperSegmentToken());
        verify(tokenStore).releaseClaim(PROCESSOR_NAME, SEGMENT_TO_MERGE);

        assertTrue(result.isDone());
        assertTrue(result.get());
    }

    @Test
    void testRunReturnsFalseThroughUnableToClaimTokenExceptionOnFetch() throws ExecutionException, InterruptedException {
        when(tokenStore.fetchSegments(PROCESSOR_NAME)).thenReturn(SEGMENT_IDS);
        when(tokenStore.fetchToken(PROCESSOR_NAME, SEGMENT_TO_MERGE))
                .thenThrow(new UnableToClaimTokenException("some exception"));

        testSubject.run();

        assertTrue(result.isDone());
        assertFalse(result.get());
    }

    @Test
    void testRunReturnsFalseThroughUnableToClaimTokenExceptionOnDelete() throws ExecutionException, InterruptedException {
        when(workPackageOne.segment()).thenReturn(SEGMENT_ZERO);
        when(workPackageOne.stopPackage())
                .thenReturn(CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(0)));
        workPackages.put(SEGMENT_TO_MERGE, workPackageOne);
        when(workPackageTwo.segment()).thenReturn(SEGMENT_ONE);
        when(workPackageTwo.stopPackage())
                .thenReturn(CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(1)));
        workPackages.put(SEGMENT_TO_BE_MERGED, workPackageTwo);

        doThrow(new UnableToClaimTokenException("some exception")).when(tokenStore)
                                                                  .deleteToken(PROCESSOR_NAME, SEGMENT_TO_BE_MERGED);

        testSubject.run();

        assertTrue(result.isDone());
        assertFalse(result.get());
    }

    @Test
    void testRunCompletesExceptionallyThroughOtherException() {
        when(workPackageOne.segment()).thenReturn(SEGMENT_ZERO);
        when(workPackageOne.stopPackage())
                .thenReturn(CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(0)));
        workPackages.put(SEGMENT_TO_MERGE, workPackageOne);
        when(workPackageTwo.segment()).thenReturn(SEGMENT_ONE);
        when(workPackageTwo.stopPackage())
                .thenReturn(CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(1)));
        workPackages.put(SEGMENT_TO_BE_MERGED, workPackageTwo);

        doThrow(new IllegalStateException("some exception")).when(tokenStore)
                                                            .deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED));

        testSubject.run();

        assertTrue(result.isDone());
        assertTrue(result.isCompletedExceptionally());
        assertThrows(ExecutionException.class, () -> result.get());
    }

    @Test
    void testDescription() {
        String result = testSubject.description();
        assertNotNull(result);
        assertTrue(result.contains("Merge"));
    }
}