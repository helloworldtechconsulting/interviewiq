package com.interviewengine.ai.service;

import com.interviewengine.shared.exception.AiServiceException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and renders the prompt templates (PRD v2.1 §7.5.1).
 *
 * <p>"Prompts live as StringTemplate {@code .st} files under
 * {@code resources/prompts} so they can be iterated without a code change."
 *
 * <p>That is the point of the whole class. Prompt wording is expected to change
 * often — §7.5.1 requires human review of the first 50 generated batches, and
 * §13.1 sets up a vendor comparison over the first ~50 interviews — and a
 * reviewer should be able to read and revise the actual prompt without going
 * through compiled Java string concatenation.
 *
 * <p>Templates are read once at startup and cached. StringTemplate's default
 * {@code <expr>} delimiters are used rather than {@code $expr$}, so the templates
 * read naturally alongside the JSON examples they contain.
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    private static final String PROMPT_PATH = "prompts/";

    public static final String QUESTION_GENERATION = "question-generation";
    public static final String EVALUATION          = "evaluation";
    public static final String FOLLOWUP            = "followup";

    private final Map<String, String> templates = new ConcurrentHashMap<>();

    /**
     * Loads every template at startup rather than lazily.
     *
     * <p>A missing or unparseable prompt should fail the deploy, not the first
     * candidate interview of the day.
     */
    @PostConstruct
    void loadTemplates() {
        for (String name : new String[]{QUESTION_GENERATION, EVALUATION, FOLLOWUP}) {
            templates.put(name, read(name));
        }
        log.info("Loaded {} prompt templates from classpath:{}", templates.size(), PROMPT_PATH);
    }

    /**
     * Renders a template with the supplied attributes.
     *
     * @param name       one of the constants on this class
     * @param attributes template attribute names to values; null values simply
     *                   leave their {@code <if(...)>} blocks unrendered
     */
    public String render(String name, Map<String, Object> attributes) {
        String source = templates.get(name);
        if (source == null) {
            throw new AiServiceException("Unknown prompt template: " + name);
        }

        // A fresh ST per render: ST instances are stateful once attributes are
        // set, and these render concurrently across virtual threads.
        ST template = new ST(new STGroup('<', '>'), source);
        attributes.forEach((key, value) -> {
            if (value != null) {
                template.add(key, value);
            }
        });

        return template.render();
    }

    private String read(String name) {
        ClassPathResource resource = new ClassPathResource(PROMPT_PATH + name + ".st");
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Prompt template '" + name + "' is missing from classpath:" + PROMPT_PATH, e);
        }
    }
}
