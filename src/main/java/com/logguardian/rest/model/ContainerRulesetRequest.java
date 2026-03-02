package com.logguardian.rest.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class ContainerRulesetRequest {
    @NonNull
    private String containerId;
    private List<String> label;
    private RuleEnum rule;
}
