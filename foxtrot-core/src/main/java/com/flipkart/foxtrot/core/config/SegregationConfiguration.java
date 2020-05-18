package com.flipkart.foxtrot.core.config;

import java.util.List;
import java.util.Map;
import lombok.Data;

/***
 Created by mudit.g on Apr, 2019
 ***/
@Data
public class SegregationConfiguration {
    //Original Table VS New Table Vs List of Events
    private Map<String, Map<String, List<String>>> tableEventConfigs;

    private List<String> tablesToBeDuplicated;
}