package org.octopusden.octopus.escrow.utilities;

import org.octopusden.octopus.escrow.dto.VersionExpressionContext;
import org.springframework.context.expression.EnvironmentAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.ParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;

public final class EscrowExpressionParser {
    private static final EscrowExpressionParser ESCROW_EXPRESSION_PARSER = new EscrowExpressionParser();

    private EscrowExpressionParser() {
    }

    public static EscrowExpressionParser getInstance() {
        return ESCROW_EXPRESSION_PARSER;
    }

    public Object parseAndEvaluate(final String spelExpression, final VersionExpressionContext expressionContext, boolean allowEnvironment) {
        if (!spelExpression.contains("$")) {
            return spelExpression;
        }
        final SpelExpressionParser expressionParser = new SpelExpressionParser();
        final Expression expression = expressionParser.parseExpression(spelExpression, new TemplateParserContext());
        final StandardEvaluationContext evaluationContext = new StandardEvaluationContext(expressionContext);
        evaluationContext.addPropertyAccessor(new EscrowMapAccessor());
        if (allowEnvironment) {
            evaluationContext.addPropertyAccessor(new EnvironmentAccessor());
        }
        return expression.getValue(evaluationContext);
    }

    private static class EscrowMapAccessor implements PropertyAccessor {

        public Class<?>[] getSpecificTargetClasses() {
            return new Class[]{Map.class};
        }

        public boolean canRead(EvaluationContext context, Object target, String name) {
            return true;
        }

        public TypedValue read(EvaluationContext context, Object target, String name) {
            final Map<?, ?> map = (Map) target;
            return new TypedValue(map.get(name));
        }

        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return true;
        }

        public void write(EvaluationContext context, Object target, String name, Object newValue) {
            Map<Object, Object> map = (Map) target;
            map.put(name, newValue);
        }
    }

    private static class TemplateParserContext implements ParserContext {

        @Override
        public boolean isTemplate() {
            return true;
        }

        @Override
        public String getExpressionPrefix() {
            return "${";
        }

        @Override
        public String getExpressionSuffix() {
            return "}";
        }
    }
}
