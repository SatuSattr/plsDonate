package click.sattr.plsDonate.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionEvaluatorTest {

    @Test
    void basicMathStillWorks() {
        assertEquals(15.0, ExpressionEvaluator.evaluateMath("10 + 5"));
        assertEquals(50.0, ExpressionEvaluator.evaluateMath("10 * 5"));
        assertEquals(2.0, ExpressionEvaluator.evaluateMath("floor(2.9)"));
    }

    // L5: division by zero yields Infinity in IEEE doubles; that previously got injected
    // into a command as the literal string "Infinity". evaluateMath must reject non-finite
    // results so the caller's fallback can take over.
    @Test
    void divisionByZeroIsRejected() {
        assertThrows(RuntimeException.class, () -> ExpressionEvaluator.evaluateMath("5 / 0"));
    }

    @Test
    void zeroOverZeroIsRejected() {
        assertThrows(RuntimeException.class, () -> ExpressionEvaluator.evaluateMath("0 / 0"));
    }

    @Test
    void numericComparisonsStillWork() {
        assertTrue(ExpressionEvaluator.evaluateCondition("5000 >= 1000"));
        assertFalse(ExpressionEvaluator.evaluateCondition("500 >= 1000"));
        assertTrue(ExpressionEvaluator.evaluateCondition("1000 <= 1000"));
    }

    @Test
    void stringEqualityStillWorks() {
        assertTrue(ExpressionEvaluator.evaluateCondition("'abc' == 'abc'"));
        assertFalse(ExpressionEvaluator.evaluateCondition("'abc' == 'xyz'"));
    }

    @Test
    void containsStillWorks() {
        assertTrue(ExpressionEvaluator.evaluateCondition("'hello world' contains 'world'"));
        assertFalse(ExpressionEvaluator.evaluateCondition("'hello world' contains 'zzz'"));
    }

    @Test
    void emptyConditionPasses() {
        assertTrue(ExpressionEvaluator.evaluateCondition(""));
        assertTrue(ExpressionEvaluator.evaluateCondition(null));
    }

    // M3: a donor-controlled operand containing an operator must not hijack parsing.
    // The condition asks "does the message contain 'items'?"; the message itself
    // contains ">=", which previously caused the parser to split on ">=" and
    // mis-evaluate to false.
    @Test
    void operatorInsideQuotedOperandIsNotTreatedAsTheOperator() {
        assertTrue(ExpressionEvaluator.evaluateCondition("'buy items >= cheap now' contains 'items'"));
        assertTrue(ExpressionEvaluator.evaluateCondition("'a == b' contains 'b'"));
        assertTrue(ExpressionEvaluator.evaluateCondition("'1 < 2 surely' contains 'surely'"));
    }

    @Test
    void quotedEqualityWithEmbeddedOperatorCharsCompares() {
        // Both operands are literally the same string containing operator chars.
        assertTrue(ExpressionEvaluator.evaluateCondition("'a>=b' == 'a>=b'"));
        assertFalse(ExpressionEvaluator.evaluateCondition("'a>=b' == 'a>=c'"));
    }
}
