package uk.ac.starlink.ast;

/** Tells test helpers outside this package which classes the library can load from a dump. */
public final class RegisteredClasses {

    private RegisteredClasses() {}

    /** Whether {@code ObjectRegistry} has a loading constructor for the class {@code className}. */
    public static boolean isRegistered(String className) {
        return ObjectRegistry.isRegistered(className);
    }
}
