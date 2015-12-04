package com.eharmony.services.mymatchesservice.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.mortbay.log.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import rx.Observable;

import com.eharmony.configuration.Configuration;
import com.eharmony.datastore.model.MatchDataFeedItemDto;
import com.eharmony.datastore.repository.MatchDataFeedItemQueryRequest;
import com.eharmony.datastore.repository.MatchDataFeedQueryRequest;
import com.eharmony.datastore.repository.MatchStoreQueryRepository;
import com.eharmony.datastore.repository.MatchStoreSaveRepository;
import com.eharmony.services.mymatchesservice.MergeModeEnum;
import com.eharmony.services.mymatchesservice.rest.MatchFeedRequestContext;
import com.eharmony.services.mymatchesservice.service.merger.FeedMergeStrategyType;
import com.eharmony.services.mymatchesservice.store.MatchDataFeedStore;
import com.eharmony.services.mymatchesservice.util.MatchStatusEnum;
import com.eharmony.services.mymatchesservice.util.MatchStatusGroupEnum;
import com.google.common.collect.Sets;

@Service
public class UserMatchesFeedServiceImpl implements UserMatchesFeedService {

    private static final Logger logger = LoggerFactory.getLogger(UserMatchesFeedServiceImpl.class);

    @Resource
    private MatchStoreQueryRepository queryRepository;

    @Resource
    private MatchStoreSaveRepository saveRepository;

    @Resource
    private MatchDataFeedStore voldemortStore;

    @Resource
    private Configuration config;

    @Value("${feed.mergeMode}")
    private MergeModeEnum mergeMode;

    @Value("${hbase.feed.parallel.fetch.enabled:true}")
    private boolean feedParallelFetchEnabled;

    @Resource(name = "matchFeedProfileFieldsList")
    private List<String> selectedProfileFields;

    @Resource
    private MatchFeedLimitsByStatusConfiguration matchFeedLimitsByStatusConfiguration;

    private static final String ALL_MATCH_STATUS = "ALL";

    @Override
    public List<MatchDataFeedItemDto> getUserMatchesInternal(long userId) {
        MatchDataFeedQueryRequest request = new MatchDataFeedQueryRequest(userId);
        try {
            Set<MatchDataFeedItemDto> matchDataFeeditems = queryRepository.getMatchDataFeed(request);
            if (CollectionUtils.isNotEmpty(matchDataFeeditems)) {
                logger.debug("found {} matches for user {}", matchDataFeeditems.size(), userId);
                return new ArrayList<MatchDataFeedItemDto>(matchDataFeeditems);
            }
        } catch (Exception ex) {
            logger.warn("exception while fetching matches", ex);
            throw new RuntimeException(ex);
        }
        logger.debug("no matches found  for user {}", userId);
        return new ArrayList<MatchDataFeedItemDto>();
    }

    @Override
    public MatchDataFeedItemDto getUserMatch(long userId, long matchId) {
        MatchDataFeedItemQueryRequest request = new MatchDataFeedItemQueryRequest(userId);
        request.setMatchId(matchId);
        try {
            MatchDataFeedItemDto matchDataFeeditem = queryRepository.getMatchDataFeedItemDto(request);
            if (matchDataFeeditem != null) {
                logger.debug("found match for user {} and matchid {}", userId, matchId);
                return matchDataFeeditem;
            }
        } catch (Exception ex) {
            logger.warn("exception while fetching matches", ex);
            throw new RuntimeException(ex);
        }
        return null;
    }

    @Override
    public Observable<Set<MatchDataFeedItemDto>> getUserMatchesFromHBaseStoreSafe(MatchFeedRequestContext requestContext) {
        Observable<Set<MatchDataFeedItemDto>> hbaseStoreFeed = Observable.defer(() -> Observable
                .just(getMatchesFeed(requestContext)));
        hbaseStoreFeed.onErrorReturn(ex -> {
            logger.warn("Exception while fetching data from hbase for user {} and returning empty set for safe method",
                    requestContext.getUserId(), ex);
            return Sets.newHashSet();
        });
        return hbaseStoreFeed;
    }

    @Deprecated
    private Set<MatchDataFeedItemDto> getMatchesFeed(MatchFeedRequestContext request) {
        try {
            if (feedParallelFetchEnabled) {
                return fetchMatchesFeedInParallel(request);
            }
            long startTime = System.currentTimeMillis();
            logger.info("Getting feed from HBase, start time {}", startTime);
            MatchDataFeedQueryRequest requestQuery = new MatchDataFeedQueryRequest(request.getUserId());
            populateWithQueryParams(request, requestQuery);
            Set<MatchDataFeedItemDto> matchdataFeed = queryRepository.getMatchDataFeed(requestQuery);
            long endTime = System.currentTimeMillis();
            logger.info("Total time to get the feed from hbase is {} MS", endTime - startTime);
            return matchdataFeed;
        } catch (Exception e) {
            logger.warn("Exception while fetching the matches from HBase store for user {}", request.getUserId(), e);
            throw new RuntimeException(e);
        }
    }

    private Set<MatchDataFeedItemDto> fetchMatchesFeedInParallel(MatchFeedRequestContext request) {
        Log.info("fetching the feed in parallel mode for user {}", request.getUserId());
        Map<MatchStatusGroupEnum, List<MatchStatusEnum>> matchStatusGroups = buildMatchesStatusGroups(request);
        Set<MatchDataFeedItemDto> matchesFeedByStatus = new HashSet<MatchDataFeedItemDto>();
        if (MapUtils.isNotEmpty(matchStatusGroups)) {
            for (MatchStatusGroupEnum matchStatusGroup : matchStatusGroups.keySet()) {
                //TODO parallel
                matchesFeedByStatus.addAll(getMatchesFromHbaseByStatusGroup(request, matchStatusGroup,
                        matchStatusGroups.get(matchStatusGroup)));
            }
        }
        return matchesFeedByStatus;
    }

    private Set<MatchDataFeedItemDto> getMatchesFromHbaseByStatusGroup(final MatchFeedRequestContext request,
            final MatchStatusGroupEnum matchStatusGroup, final List<MatchStatusEnum> matchStuses) {
        try {
            MatchDataFeedQueryRequest requestQuery = new MatchDataFeedQueryRequest(request.getUserId());
            pupulateRequestWithQueryParams(request, matchStatusGroup, matchStuses, requestQuery);

            Set<MatchDataFeedItemDto> matchdataFeed = queryRepository.getMatchDataFeed(requestQuery);
            return matchdataFeed;
        } catch (Exception e) {
            logger.warn("Exception while fetching the matches from HBase store for user {} and group {}",
                    request.getUserId(), matchStatusGroup, e);
            throw new RuntimeException(e);
        }
    }

    private void pupulateRequestWithQueryParams(final MatchFeedRequestContext request,
            final MatchStatusGroupEnum matchStatusGroup, final List<MatchStatusEnum> matchStuses,
            MatchDataFeedQueryRequest requestQuery) {
        FeedMergeStrategyType strategy = request.getFeedMergeType();
        if (strategy != null && strategy == FeedMergeStrategyType.VOLDY_FEED_WITH_PROFILE_MERGE) {
            requestQuery.setSelectedFields(selectedProfileFields);
        }
        List<Integer> statuses = new ArrayList<Integer>();
        if (CollectionUtils.isNotEmpty(matchStuses)) {
            for (MatchStatusEnum matchStatus : matchStuses) {
                statuses.add(matchStatus.toInt());
            }
            requestQuery.setMatchStatusFilters(statuses);
            pupulateQueryWithLimitParams(request, matchStatusGroup, requestQuery);

        }
    }

    private void pupulateQueryWithLimitParams(final MatchFeedRequestContext request,
            final MatchStatusGroupEnum matchStatusGroup, MatchDataFeedQueryRequest requestQuery) {
        Integer feedLimit = null;
        if (request.isFallbackRequest()) {
            feedLimit = matchFeedLimitsByStatusConfiguration.getFallbackFeedLimitForGroup(matchStatusGroup);
        } else {
            feedLimit = matchFeedLimitsByStatusConfiguration.getDefaultFeedLimitForGroup(matchStatusGroup);
        }
        if (feedLimit != null) {
            requestQuery.setStartPage(1);
            requestQuery.setPageSize(feedLimit);
        }
    }

    @Deprecated
    private void populateWithQueryParams(MatchFeedRequestContext request, MatchDataFeedQueryRequest requestQuery) {
        Set<String> statuses = request.getMatchFeedQueryContext().getStatuses();
        List<Integer> matchStatuses = new ArrayList<Integer>();
        if (CollectionUtils.isNotEmpty(statuses)) {
            for (String status : statuses) {
                if (ALL_MATCH_STATUS.equalsIgnoreCase(status)) {
                    matchStatuses = new ArrayList<Integer>();
                    break;
                }
                MatchStatusEnum statusEnum = MatchStatusEnum.fromName(status);
                if (statusEnum != null) {
                    matchStatuses.add(statusEnum.toInt());
                }
            }
            if (CollectionUtils.isNotEmpty(matchStatuses)) {
                requestQuery.setMatchStatusFilters(matchStatuses);
            }
        }
        FeedMergeStrategyType strategy = request.getFeedMergeType();
        if (strategy != null && strategy == FeedMergeStrategyType.VOLDY_FEED_WITH_PROFILE_MERGE) {
            requestQuery.setSelectedFields(selectedProfileFields);
        }
    }

    private Map<MatchStatusGroupEnum, List<MatchStatusEnum>> buildMatchesStatusGroups(MatchFeedRequestContext request) {
        Map<MatchStatusGroupEnum, List<MatchStatusEnum>> statusGroups = new HashMap<MatchStatusGroupEnum, List<MatchStatusEnum>>();
        Set<String> statuses = request.getMatchFeedQueryContext().getStatuses();

        if (CollectionUtils.isNotEmpty(statuses)) {
            for (String status : statuses) {
                MatchStatusEnum matchStatus = MatchStatusEnum.fromName(status);
                if (matchStatus == null) {
                    if (status.equalsIgnoreCase("all")) {
                        populateMapWithAllStatuses(statusGroups);
                        break;
                    }
                    logger.warn("Requested match status {} is not valid", status);
                    continue;
                }
                switch (matchStatus) {
                case NEW:
                    List<MatchStatusEnum> newMmatchStuses = statusGroups.get(MatchStatusGroupEnum.NEW);
                    if (CollectionUtils.isEmpty(newMmatchStuses)) {
                        newMmatchStuses = new ArrayList<MatchStatusEnum>();
                    }
                    newMmatchStuses.add(matchStatus);
                    statusGroups.put(MatchStatusGroupEnum.NEW, newMmatchStuses);
                case ARCHIVED:
                    List<MatchStatusEnum> archiveMatchStuses = statusGroups.get(MatchStatusGroupEnum.ARCHIVE);
                    if (CollectionUtils.isEmpty(archiveMatchStuses)) {
                        archiveMatchStuses = new ArrayList<MatchStatusEnum>();
                    }
                    archiveMatchStuses.add(matchStatus);
                    statusGroups.put(MatchStatusGroupEnum.ARCHIVE, archiveMatchStuses);
                case OPENCOMM:
                case MYTURN:
                case THEIRTURN:
                    List<MatchStatusEnum> commMatchStuses = statusGroups.get(MatchStatusGroupEnum.COMMUNICATION);
                    if (CollectionUtils.isEmpty(commMatchStuses)) {
                        commMatchStuses = new ArrayList<MatchStatusEnum>();
                    }
                    commMatchStuses.add(matchStatus);
                    statusGroups.put(MatchStatusGroupEnum.COMMUNICATION, commMatchStuses);
                case CLOSED:
                    logger.warn("Closed matches are not supported in this system...");
                }
            }
        }

        if (MapUtils.isEmpty(statusGroups)) {
            logger.warn("feed request for user {} doesn't contain any status, returning all matches...",
                    request.getUserId());
            populateMapWithAllStatuses(statusGroups);
        }
        return statusGroups;
    }

    private void populateMapWithAllStatuses(Map<MatchStatusGroupEnum, List<MatchStatusEnum>> statusGroups) {
        List<MatchStatusEnum> newMatchStuses = new ArrayList<MatchStatusEnum>();
        List<MatchStatusEnum> commMatchStuses = new ArrayList<MatchStatusEnum>();
        List<MatchStatusEnum> archiveMatchStuses = new ArrayList<MatchStatusEnum>();
        newMatchStuses.add(MatchStatusEnum.NEW);
        commMatchStuses.add(MatchStatusEnum.MYTURN);
        commMatchStuses.add(MatchStatusEnum.THEIRTURN);
        commMatchStuses.add(MatchStatusEnum.OPENCOMM);
        archiveMatchStuses.add(MatchStatusEnum.ARCHIVED);
        statusGroups.put(MatchStatusGroupEnum.NEW, newMatchStuses);
        statusGroups.put(MatchStatusGroupEnum.COMMUNICATION, commMatchStuses);
        statusGroups.put(MatchStatusGroupEnum.ARCHIVE, archiveMatchStuses);

    }

}
