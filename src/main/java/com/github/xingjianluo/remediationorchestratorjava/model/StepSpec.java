package com.github.xingjianluo.remediationorchestratorjava.model;

import java.util.Map;

/** 策略中声明的一个修复步骤模板。 */
public class StepSpec {
    public StepType type;
    public String name;
    public Map<String, String> params = Map.of();
}
