package com.mathvision.model;

/**
 * PocketFlow action names used for routed transitions between workflow nodes.
 */
public final class WorkflowActions {

    private WorkflowActions() {}

    public static final String FIX_CODE = "fix_code";
    public static final String RETRY_CODE_GENERATION = "retry_code_generation";
    public static final String RETRY_CODE_EVALUATION = "retry_code_evaluation";
    public static final String RETRY_RENDER = "retry_render";
}
