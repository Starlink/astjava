/**
 * A port of the parts of cminpack, the C version of the MINPACK least-squares library, that AST
 * uses: the Levenberg-Marquardt minimiser {@code lmder1} and the routines it calls, which PolyMap's
 * {@code astPolyTran} fits with. Apache Commons Math's {@code LevenbergMarquardtOptimizer} is a
 * Java translation of the same MINPACK routine and could replace this port, at the cost of a
 * runtime dependency.
 *
 * <p>This product includes software developed by the University of Chicago, as Operator of Argonne
 * National Laboratory; the MINPACK copyright notice is at the head of each
 * source file of the package.
 *
 * <p>This package is internal to the AST library and is not part of its API: its classes may change
 * or disappear in any release.
 */
package uk.ac.starlink.ast.internal.cminpack;
