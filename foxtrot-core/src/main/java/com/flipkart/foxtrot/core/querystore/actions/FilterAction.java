/**
 * Copyright 2014 Flipkart Internet Pvt. Ltd.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.flipkart.foxtrot.core.querystore.actions;

import com.flipkart.foxtrot.common.ActionResponse;
import com.flipkart.foxtrot.common.Document;
import com.flipkart.foxtrot.common.query.Filter;
import com.flipkart.foxtrot.common.query.Query;
import com.flipkart.foxtrot.common.query.QueryResponse;
import com.flipkart.foxtrot.common.query.ResultSort;
import com.flipkart.foxtrot.common.util.CollectionUtils;
import com.flipkart.foxtrot.core.common.Action;
import com.flipkart.foxtrot.core.config.ElasticsearchTuningConfig;
import com.flipkart.foxtrot.core.exception.FoxtrotExceptions;
import com.flipkart.foxtrot.core.querystore.actions.spi.AnalyticsLoader;
import com.flipkart.foxtrot.core.querystore.actions.spi.AnalyticsProvider;
import com.flipkart.foxtrot.core.querystore.impl.ElasticsearchUtils;
import com.flipkart.foxtrot.core.querystore.query.ElasticSearchQueryGenerator;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * User: Santanu Sinha (santanu.sinha@flipkart.com)
 * Date: 24/03/14
 * Time: 1:00 PM
 */
@AnalyticsProvider(opcode = "query", request = Query.class, response = QueryResponse.class, cacheable = false)
public class FilterAction extends Action<Query> {
    private static final Logger logger = LoggerFactory.getLogger(FilterAction.class);
    private ElasticsearchTuningConfig elasticsearchTuningConfig;

    public FilterAction(Query parameter, AnalyticsLoader analyticsLoader) {
        super(parameter, analyticsLoader);
        this.elasticsearchTuningConfig = analyticsLoader.getElasticsearchTuningConfig();
    }

    @Override
    public void preprocess() {
        getParameter().setTable(ElasticsearchUtils.getValidTableName(getParameter().getTable()));
        if(null == getParameter().getSort()) {
            ResultSort resultSort = new ResultSort();
            resultSort.setField("_timestamp");
            resultSort.setOrder(ResultSort.Order.desc);
            getParameter().setSort(resultSort);
        }
    }

    @Override
    public String getMetricKey() {
        return getParameter().getTable();
    }

    @Override
    public String getRequestCacheKey() {
        long filterHashKey = 0L;
        Query query = getParameter();
        if(null != query.getFilters()) {
            for(Filter filter : query.getFilters()) {
                filterHashKey += 31 * filter.hashCode();
            }
        }
        filterHashKey += 31 * (query.getSort() != null ? query.getSort()
                .hashCode() : "SORT".hashCode());

        return String.format("%s-%d-%d-%d", query.getTable(), query.getFrom(), query.getLimit(), filterHashKey);
    }

    @Override
    public void validateImpl(Query parameter) {
        List<String> validationErrors = new ArrayList<>();
        if(CollectionUtils.isNullOrEmpty(parameter.getTable())) {
            validationErrors.add("table name cannot be null or empty");
        }
        if(parameter.getSort() == null) {
            validationErrors.add("sort order needs to be specified");
        }

        if(parameter.getFrom() < 0) {
            validationErrors.add("from must be non-negative integer");
        }

        if(parameter.getLimit() <= 0) {
            validationErrors.add("limit must be positive integer");
        }

        if(!CollectionUtils.isNullOrEmpty(validationErrors)) {
            throw FoxtrotExceptions.createMalformedQueryException(parameter, validationErrors);
        }
    }

    @Override
    public ActionResponse execute(Query parameter) {
        List<String> ids = new ArrayList<>();
        try {
            SearchRequest searchRequest = getRequestBuilder(parameter);
            SearchResponse searchResponse = getConnection()
                    .getClient()
                    .search(searchRequest, RequestOptions.DEFAULT);

            String scrollId = searchResponse.getScrollId();

            SearchHits searchHits = searchResponse.getHits();
            for(SearchHit searchHit : searchHits) {
                ids.add(searchHit.getId());
            }
            while(ids.size() < parameter.getLimit()){
                SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
                scrollRequest.scroll(TimeValue.timeValueSeconds(elasticsearchTuningConfig.getScrollTimeInSeconds()));
                SearchResponse searchScrollResponse = getConnection().getClient().scroll(scrollRequest, RequestOptions.DEFAULT);
                scrollId = searchScrollResponse.getScrollId();
                SearchHits hits = searchScrollResponse.getHits();
                if (hits.getHits().length == 0){
                    return new QueryResponse(getQueryStore().getAll(parameter.getTable(), ids, true), ids.size());
                }
                for(SearchHit searchHit : hits) {
                    ids.add(searchHit.getId());
                }
            }
            if (ids.size() > parameter.getLimit()){
                ids = new ArrayList<>(ids.subList(0, parameter.getLimit()));
            }
            return new QueryResponse(getQueryStore().getAll(parameter.getTable(), ids, true), ids.size());
        }catch (IOException e){
            throw FoxtrotExceptions.createQueryExecutionException(parameter, e);
        }
    }

    @Override
    public SearchRequest getRequestBuilder(Query parameter) {
        SearchRequest searchRequest = new SearchRequest(ElasticsearchUtils.getIndices(parameter.getTable(), parameter))
                .indicesOptions(Utils.indicesOptions())
                .types(ElasticsearchUtils.DOCUMENT_TYPE_NAME)
                .searchType(SearchType.QUERY_THEN_FETCH)
                .source(new SearchSourceBuilder()
                                .timeout(new TimeValue(getGetQueryTimeout(), TimeUnit.MILLISECONDS))
                                .size(elasticsearchTuningConfig.getScrollSize())
                                .query(new ElasticSearchQueryGenerator().genFilter(parameter.getFilters()))
                                .from(parameter.getFrom())
                                .sort(Utils.storedFieldName(parameter.getSort().getField()),
                                      ResultSort.Order.desc == parameter.getSort().getOrder()
                                      ? SortOrder.DESC : SortOrder.ASC));
        searchRequest.scroll(TimeValue.timeValueSeconds(elasticsearchTuningConfig.getScrollTimeInSeconds()));
        return searchRequest;
    }

    @Override
    public ActionResponse getResponse(org.elasticsearch.action.ActionResponse response, Query parameter) {
        List<String> ids = new ArrayList<>();
        SearchHits searchHits = ((SearchResponse)response).getHits();
        for(SearchHit searchHit : searchHits) {
            ids.add(searchHit.getId());
        }
        if(ids.isEmpty()) {
            return new QueryResponse(Collections.<Document>emptyList(), 0);
        }
        return new QueryResponse(getQueryStore().getAll(parameter.getTable(), ids, true), searchHits.getTotalHits());
    }
}
