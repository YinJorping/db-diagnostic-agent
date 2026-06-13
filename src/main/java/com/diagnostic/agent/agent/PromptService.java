package com.diagnostic.agent.agent;

import com.diagnostic.agent.common.BusinessException;
import com.diagnostic.agent.eval.PromptOverrideManager;
import com.diagnostic.agent.repository.PromptTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Prompt 模板服务。从数据库加载模板并替换变量占位符。
 */
@Component
public class PromptService {

    private final PromptTemplateRepository repository;

    @Autowired(required = false)
    private PromptOverrideManager promptOverrideManager;

    public PromptService(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * 按 key 加载模板原始内容。模板不存在时抛出 BusinessException。
     * 优先查询 PromptOverrideManager（评测时的运行时覆盖），无覆盖则走 DB。
     */
    public String loadTemplate(String templateKey) {
        if (promptOverrideManager != null) {
            String override = promptOverrideManager.get(templateKey);
            if (override != null) {
                return override;
            }
        }
        return repository.findByTemplateKey(templateKey)
                .orElseThrow(() -> BusinessException.notFound("模板未找到: " + templateKey))
                .getContent();
    }

    /**
     * 加载模板并替换变量占位符 {variable}。
     */
    public String loadAndRender(String templateKey, Map<String, String> variables) {
        String template = loadTemplate(templateKey);
        return render(template, variables);
    }

    /**
     * 替换模板中的 {key} 占位符。
     * 未提供对应值的占位符保持原样，空 map 不修改模板。
     */
    String render(String template, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
