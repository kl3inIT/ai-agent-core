package com.vn.agent.tools.jpql;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and reconciles the named parameters of an LLM-authored JPQL query.
 *
 * <p>Ported from the jmix-crm reference assistant. Lets {@link AiJpqlQueryService} (a) bind only
 * the parameters the query actually references, and (b) recover from a "query argument N is not
 * declared" error by dropping the offending parameter and retrying once.
 */
@Component
public class JpqlNamedParameterParser {

    // Match :name but not :: (PostgreSQL cast operator) — negative lookbehind on a leading colon.
    private static final Pattern NAMED_PARAMETER_PATTERN = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");

    public Set<String> extractNames(String jpqlQuery) {
        Set<String> names = new HashSet<>();
        if (jpqlQuery == null || jpqlQuery.isBlank()) {
            return names;
        }
        Matcher matcher = NAMED_PARAMETER_PATTERN.matcher(jpqlQuery);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    public boolean contains(JpqlParameters parameters, String parameterName) {
        if (parameters == null) {
            return false;
        }
        return parameters.safeParameters().stream()
                .anyMatch(param -> param.parameterName().equals(parameterName));
    }

    public JpqlParameters without(JpqlParameters parameters, String parameterName) {
        if (parameters == null) {
            return JpqlParameters.empty();
        }
        List<JpqlParameter> filteredList = parameters.safeParameters().stream()
                .filter(param -> !param.parameterName().equals(parameterName))
                .toList();
        return new JpqlParameters(filteredList);
    }

    /** Extract the offending parameter name from an EclipseLink/Jmix "Query argument X ..." error. */
    public String extractUnknownParameterName(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        String marker = "Query argument ";
        int start = errorMessage.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int from = start + marker.length();
        int to = errorMessage.indexOf(' ', from);
        if (to < 0) {
            return null;
        }
        return errorMessage.substring(from, to).trim();
    }
}
