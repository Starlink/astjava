/**
 * A pure-Java port of the Starlink AST library for handling world coordinate systems.
 *
 * <p>The public classes and their methods follow those of jniast, the JNI interface to the C
 * library, so applications written against jniast can use this library in its place. Behaviour
 * follows the C library.
 *
 * <p>No object may be used by two threads at once. A thread that needs an object another thread
 * is using should take its own {@code copy()}.
 */
package uk.ac.starlink.ast;
