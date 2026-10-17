package net.velocity.inference;

/** Relationship validation shared by configured boundaries; zero capacity means undeclared. */
final class InferenceCapacity {

    private InferenceCapacity() {
    }

    static boolean fits(long capacity, long input, long output, long margin) {
        if (capacity == 0) {
            return true;
        }
        try {
            return Math.addExact(Math.addExact(input, output), margin) <= capacity;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
