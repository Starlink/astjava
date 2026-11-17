/* mathmap_probe -- MathMap behaviour of the pinned C library that no fixture
 * or oracle holds: the value of every operator, function and constant of
 * the symbol table at a set of arguments, bad and out-of-domain ones
 * included; the status and messages of each malformed function; the
 * random-number functions for a given Seed, on copies and on two MathMaps
 * used in turn; the attribute strings; and native dumps through a Channel
 * and a FitsChan, read back.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -I<build> -o mathmap_probe mathmap_probe.c <build>/libast.a
 *      <build>/libast_pal.a <build>/libast_err.a <build>/libast_grf3d.a
 *      <build>/libast_grf_2.0.a <build>/libast_grf_3.2.a
 *      <build>/libast_grf_5.6.a <build>/libast_cminpack.a -lyaml -lm
 * and run as ./mathmap_probe > mathmap_probe.out 2>&1, then trailing white
 * space removed from every line (MathMapTest compares lines without it). */
#include <float.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include "ast.h"

static void head( const char *name ) {
   printf( "== %s\n", name );
}

/* The arguments at which each expression of the "evaluate" case is
   evaluated. */
static const double xs[] = { -2.5, -1.0, -0.5, 0.0, 0.25, 1.0, 2.0, 1000.0,
                             1.0e300, -1.0e300, AST__BAD };
#define NX ( sizeof( xs ) / sizeof( xs[ 0 ] ) )

/* The expressions of the "evaluate" case, each the right-hand side of the
   forward function "y = <expression>" of a MathMap with input "x". */
static const char *exprs[] = {
   "x", "<bad>", "<dig>", "<epsilon>", "<mant_dig>", "<max>", "<max_10_exp>",
   "<max_exp>", "<min>", "<min_10_exp>", "<min_exp>", "<radix>", "<rounds>",
   "<e>", "<pi>",
   "abs(x)", "acos(x)", "acosd(x)", "acosh(x)", "acoth(x)", "acsch(x)",
   "aint(x)", "asech(x)", "asin(x)", "asind(x)", "asinh(x)", "atan(x)",
   "atand(x)", "atanh(x)", "ceil(x)", "cos(x)", "cosd(x)", "cosh(x)",
   "coth(x)", "csch(x)", "exp(x)", "fabs(x)", "floor(x)", "int(x)",
   "isbad(x)", "log(x)", "log10(x)", "nint(x)", "sech(x)", "sin(x)",
   "sinc(x)", "sind(x)", "sinh(x)", "sqr(x)", "sqrt(x)", "tan(x)", "tand(x)",
   "tanh(x)",
   "atan2(x,0.5)", "atan2(0.5,x)", "atan2d(x,-0.5)", "dim(x,1)", "dim(1,x)",
   "fmod(x,0.75)", "mod(x,0)", "mod(7.5,x)", "pow(x,0.5)", "pow(x,2)",
   "pow(x,-1)", "pow(2,x)", "sign(2,x)", "sign(x,-1)", "qif(x,2,3)",
   "qif(1,x,3)", "qif(0,2,x)", "max(x,1,-1)", "min(x,1,-1)", "max(x,<bad>)",
   "min(2,x)",
   "-x", "+x", "!x", ".not.x", "x+1", "x-1", "x*2", "x/0.5", "x/0", "1/x",
   "x**2", "x**0.5", "x**-1", "x<<2", "x>>1", "x<<x", "x<1", "x.lt.1",
   "x<=1", "x.le.1", "x>1", "x.gt.1", "x>=1", "x.ge.1", "x==1", "x.eq.1",
   "x!=1", "x.ne.1", "x&3", "x^3", "x|3", "x&&1", "x&&0", "x.and.1", "x^^1",
   "x||0", "x||1", "x.or.0", "x.eqv.1", "x.neqv.1", "x.xor.1",
   "x*1e300", "x+<max>", "x-<max>", "<max>/x", "x/<max>", "x*x*x",
   "1e999", "1e999-1e999", "sin(1e999)",
   "-x**2", "!x**2", "2**-x**2", "-x*3", "x<<1<3", "x.or.x.xor.x",
   "x||x^^x", "x^^x.eqv.x", "x<3==1", "(x+1)*2", "sin(x)**2+cos(x)**2",
   "max(x,1,2,3,4)-min(x,-1,-2)", "1.e1+1.d1+.5e-1+2.5d+1", "x.or.1",
   NULL
};

/* Reports a failed call, then clears the status. */
static int failed( void ) {
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
      return 1;
   }
   return 0;
}

/* A MathMap of the forward and inverse functions given, NULL-terminated,
   and the status if it cannot be made. */
static AstMathMap *make( int nin, int nout, const char **fwd,
                         const char **inv ) {
   int nfwd = 0, ninv = 0;
   while( fwd[ nfwd ] ) nfwd++;
   while( inv[ ninv ] ) ninv++;
   AstMathMap *m = astMathMap( nin, nout, nfwd, fwd, ninv, inv, " " );
   if( failed() ) return NULL;
   printf( "made\n" );
   return m;
}

/* The status and messages of a MathMap with input "x", output "y" and the
   forward function "y = <expr>". */
static void bad_expr( const char *expr ) {
   char fwd[ 200 ];
   sprintf( fwd, "y = %s", expr );
   const char *f[] = { fwd, NULL };
   const char *i[] = { "x", NULL };
   printf( "-- %s\n", expr );
   make( 1, 1, f, i );
}

/* Values of a 1-input, 1-output MathMap applied forward to "npoint"
   copies of "x". */
static void draws( AstMapping *m, int npoint, double x ) {
   double in[ 100 ], out[ 100 ];
   for( int i = 0; i < npoint; i++ ) in[ i ] = x;
   astTran1( m, npoint, in, 1, out );
   if( failed() ) return;
   for( int i = 0; i < npoint; i++ ) printf( "%.17g\n", out[ i ] );
}

static AstMathMap *random1( const char *expr, int seed ) {
   char fwd[ 200 ];
   sprintf( fwd, "y = %s", expr );
   const char *f[] = { fwd, NULL };
   const char *i[] = { "x", NULL };
   AstMathMap *m = astMathMap( 1, 1, 1, f, 1, i, " " );
   astSetI( m, "Seed", seed );
   return m;
}

static void attrs( AstMathMap *m ) {
   printf( "SimpFI %s test %d\n", astGetC( m, "SimpFI" ), astTest( m, "SimpFI" ) );
   printf( "SimpIF %s test %d\n", astGetC( m, "SimpIF" ), astTest( m, "SimpIF" ) );
   int set = astTest( m, "Seed" );
   if( set ) {
      printf( "Seed %s test 1\n", astGetC( m, "Seed" ) );
   } else {
      printf( "Seed test 0\n" );
   }
}

/* The dump, then the cards of a native FitsChan write, then the dump of
   the Object read back from them. */
static void dumps( const char *name, AstObject *obj ) {
   head( name );
   astShow( obj );
   AstFitsChan *fc = astFitsChan( NULL, NULL, "Encoding=NATIVE" );
   astWrite( fc, obj );
   printf( "-- fits\n" );
   char card[ 81 ];
   astClear( fc, "Card" );
   while( astFindFits( fc, "%f", card, 1 ) ) printf( "%s\n", card );
   astClear( fc, "Card" );
   AstObject *back = astRead( fc );
   printf( "-- read back from fits\n" );
   astShow( back );
}

static void tran2( AstMapping *m, double x, double y, int fwd ) {
   double xo, yo;
   astTran2( m, 1, &x, &y, fwd, &xo, &yo );
   if( failed() ) return;
   printf( "%s %.17g %.17g -> %.17g %.17g\n", fwd ? "fwd" : "inv", x, y, xo, yo );
}

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   /* Every operator, function and constant. */
   head( "evaluate" );
   for( int ie = 0; exprs[ ie ]; ie++ ) {
      char fwd[ 200 ];
      sprintf( fwd, "y = %s", exprs[ ie ] );
      const char *f[] = { fwd, NULL };
      const char *i[] = { "x", NULL };
      AstMathMap *m = astMathMap( 1, 1, 1, f, 1, i, " " );
      if( failed() ) continue;
      double out[ NX ];
      astTran1( m, (int) NX, xs, 1, out );
      if( failed() ) continue;
      for( size_t ix = 0; ix < NX; ix++ ) {
         printf( "%s @ %.17g = %.17g\n", exprs[ ie ], xs[ ix ], out[ ix ] );
      }
   }

   /* Malformed expressions. */
   head( "expression_errors" );
   {
      const char *bad[] = {
         "1.or.0", "1 .and. 1", "x .not. 1", "2x", "x(1)", "x!", "(x)(x)",
         "2.eq.2", ".eq.x", "1.e", "1.0.5", "x<bad>", "sin", "q", "*x",
         "max(1,)", "x,1", "(x,1)", "sin((x,1))", "max(x,(1,2))", "x,1)+(",
         "x)", "x)+(", "sin(x,1)", "atan2(x)", "atan2(x,1,2)", "qif(1,2)",
         "max(x)", "max(x)+(", "min(x,)", "x+", "x+)", "(,x)", ")", "(x",
         "sin(x", "1e", "1e+", "1.5e-x", "x y", "x**", "--x", "-+x",
         "x+-1", "x--1", "x*-1", ".5.", "1d5d", "<pi", "<pie>", NULL };
      for( int ib = 0; bad[ ib ]; ib++ ) bad_expr( bad[ ib ] );
   }

   /* Unary operators that follow another operator. */
   head( "unary_sequences" );
   {
      const char *ok[] = { "--x", "-+x", "+-x", "x+-1", "x--1", "x*-1",
                           "-(-x)", "x**--2", "!!x", "-!x", NULL };
      for( int io = 0; ok[ io ]; io++ ) {
         char fwd[ 200 ];
         sprintf( fwd, "y = %s", ok[ io ] );
         const char *f[] = { fwd, NULL };
         const char *i[] = { "x", NULL };
         printf( "-- %s\n", ok[ io ] );
         AstMathMap *m = astMathMap( 1, 1, 1, f, 1, i, " " );
         if( failed() ) continue;
         double x = 2.0, y;
         astTran1( m, 1, &x, 1, &y );
         printf( "%.17g\n", y );
      }
   }

   /* Errors in the functions as a whole. */
   head( "function_errors" );
   {
      const char *f1[] = { "y = x1 + 1", NULL };
      const char *i1[] = { "x + 1", NULL };
      printf( "-- varin_expression\n" );
      make( 1, 1, f1, i1 );
      const char *f2[] = { "y = x", NULL };
      const char *i2[] = { "x <= y", NULL };
      printf( "-- varin_relational\n" );
      make( 1, 1, f2, i2 );
      const char *i3[] = { "x + 1 = y", NULL };
      printf( "-- varin_lhs\n" );
      make( 1, 1, f2, i3 );
      const char *i4[] = { "_x", NULL };
      printf( "-- varin_underscore\n" );
      make( 1, 1, f2, i4 );
      const char *i5[] = { "1x", NULL };
      printf( "-- varin_digit\n" );
      make( 1, 1, f2, i5 );
      const char *f6[] = { "y = x + 1", NULL };
      const char *i6[] = { "x = y - 1", "x = 2", NULL };
      printf( "-- duvar_inverse\n" );
      make( 1, 1, f6, i6 );
      const char *i7[] = { "y", NULL };
      printf( "-- duvar_input_output\n" );
      make( 1, 1, f2, i7 );
      const char *i8[] = { "= y - 1", NULL };
      printf( "-- misvn_no_lhs\n" );
      make( 1, 1, f6, i8 );
      const char *i9[] = { "", NULL };
      printf( "-- misvn_empty\n" );
      make( 1, 1, f6, i9 );
      const char *i10[] = { "   ", NULL };
      printf( "-- misvn_blank\n" );
      make( 1, 1, f6, i10 );
      const char *f11[] = { "y ==", NULL };
      const char *i11[] = { "x", NULL };
      printf( "-- miopa_double_equals\n" );
      make( 1, 1, f11, i11 );
      const char *f12[] = { "y = = x", NULL };
      printf( "-- miopa_equals_equals\n" );
      make( 1, 1, f12, i11 );
      const char *f13[] = { "y =", NULL };
      printf( "-- norhs_empty\n" );
      make( 1, 1, f13, i11 );
      const char *f14[] = { "t", "y = x", NULL };
      printf( "-- norhs_mixed\n" );
      make( 1, 1, f14, i11 );
      const char *f15[] = { "a = x", "b = a", "y = b + c", NULL };
      printf( "-- udvof_third_function\n" );
      make( 1, 1, f15, i11 );
      const char *f16[] = { "y = x", NULL };
      const char *i16[] = { "t = y", "x = t + <bad", NULL };
      printf( "-- inverse_second_function\n" );
      make( 1, 1, f16, i16 );
      const char *f17[] = { "a = x", "b = a", "y = b", NULL };
      const char *i17[] = { "c = y", "a = c", "x = a", NULL };
      printf( "-- duvar_numbering\n" );
      make( 1, 1, f17, i17 );
      const char *f18[] = { "p = 1", "q = 2", "y = x", NULL };
      const char *i18[] = { "x = 1", NULL };
      const char *f18b[] = { "p = 1", "y = 2", "q = x", NULL };
      printf( "-- extra_forward_ok\n" );
      make( 1, 1, f18, i18 );
      printf( "-- extra_forward_varin\n" );
      const char *f19[] = { "p = 1", "y-2 = x", NULL };
      make( 1, 1, f19, i18 );
      printf( "-- extra_inverse_varin\n" );
      const char *i20[] = { "p q = 1", "x = y", NULL };
      make( 1, 1, f18b, i20 );
      printf( "-- misvn_primary\n" );
      const char *i21[] = { "c = y", "=1", NULL };
      make( 1, 1, f16, i21 );
   }

   /* The numbers of inputs, outputs and functions. */
   head( "count_errors" );
   {
      const char *f[] = { "y = x", NULL };
      const char *i[] = { "x = y", NULL };
      const char *f2[] = { "y = x", "z = x", NULL };
      const char *i2[] = { "x = y", "w = y", NULL };
      printf( "-- nin_zero\n" );
      make( 0, 1, f, i );
      printf( "-- nout_zero\n" );
      make( 1, 0, f, i );
      printf( "-- nin_negative\n" );
      make( -1, 1, f, i );
      printf( "-- too_few_forward\n" );
      make( 1, 2, f, i2 );
      printf( "-- too_few_inverse\n" );
      make( 2, 1, f2, i );
   }

   /* Transformations with intermediate variables, undefined and inverted. */
   head( "transformations" );
   {
      const char *f[] = { "r = sqrt( x * x + y * y )",
                          "rout = r * ( 1 + 0.1 * r * r )",
                          "theta = atan2( y, x )",
                          "xout = rout * cos( theta )",
                          "yout = rout * sin( theta )", NULL };
      const char *i[] = { "x", "y", NULL };
      AstMathMap *m = make( 2, 2, f, i );
      printf( "TranForward %d TranInverse %d\n", astGetI( m, "TranForward" ),
              astGetI( m, "TranInverse" ) );
      tran2( (AstMapping *) m, 3.0, 4.0, 1 );
      tran2( (AstMapping *) m, -1.0, 0.5, 1 );
      tran2( (AstMapping *) m, AST__BAD, 0.5, 1 );
      tran2( (AstMapping *) m, 3.0, 4.0, 0 );
      astInvert( m );
      printf( "TranForward %d TranInverse %d\n", astGetI( m, "TranForward" ),
              astGetI( m, "TranInverse" ) );
      tran2( (AstMapping *) m, 3.0, 4.0, 0 );
      const char *pf[] = { "r = sqrt( x * x + y * y )", "theta = atan2( y, x )",
                           NULL };
      const char *pi[] = { "x = r * cos( theta )", "y = r * sin( theta )",
                           NULL };
      AstMathMap *p = make( 2, 2, pf, pi );
      tran2( (AstMapping *) p, 3.0, 4.0, 1 );
      tran2( (AstMapping *) p, 5.0, 0.9272952180016122, 0 );
      astInvert( p );
      tran2( (AstMapping *) p, 5.0, 0.9272952180016122, 1 );
      const char *uf[] = { "y", NULL };
      const char *ui[] = { "x = 2 * y", NULL };
      AstMathMap *u = make( 1, 1, uf, ui );
      printf( "TranForward %d TranInverse %d\n", astGetI( u, "TranForward" ),
              astGetI( u, "TranInverse" ) );
      double x = 3.0, y;
      astTran1( u, 1, &x, 1, &y );
      failed();
      astTran1( u, 1, &x, 0, &y );
      printf( "inv %.17g\n", y );
   }

   /* Random numbers for a given Seed. */
   head( "random_seeds" );
   {
      int seeds[] = { 1, 12345, -7, 0, INT_MAX, INT_MIN };
      for( int is = 0; is < 6; is++ ) {
         printf( "-- rand seed %d\n", seeds[ is ] );
         draws( (AstMapping *) random1( "rand(0,1)", seeds[ is ] ), 6, 0.0 );
      }
      for( int is = 0; is < 3; is++ ) {
         printf( "-- gauss seed %d\n", seeds[ is ] );
         draws( (AstMapping *) random1( "gauss(x,1)", seeds[ is ] ), 7, 0.0 );
      }
      printf( "-- rand range\n" );
      draws( (AstMapping *) random1( "rand(x,-3)", 99 ), 5, 10.0 );
      printf( "-- gauss mean sd\n" );
      draws( (AstMapping *) random1( "gauss(x,-2)", 99 ), 5, 10.0 );
      printf( "-- poisson small\n" );
      draws( (AstMapping *) random1( "poisson(x)", 3 ), 10, 3.5 );
      printf( "-- poisson large\n" );
      draws( (AstMapping *) random1( "poisson(x)", 3 ), 10, 20.0 );
      printf( "-- poisson huge mean\n" );
      draws( (AstMapping *) random1( "poisson(x)", 3 ), 2, 1.0e40 );
      printf( "-- poisson zero\n" );
      draws( (AstMapping *) random1( "poisson(x)", 3 ), 2, 0.0 );
      printf( "-- poisson negative\n" );
      draws( (AstMapping *) random1( "poisson(x)", 3 ), 2, -1.0 );
      printf( "-- poisson bad\n" );
      draws( (AstMapping *) random1( "poisson(x)", 3 ), 2, AST__BAD );
      printf( "-- rand bad\n" );
      draws( (AstMapping *) random1( "rand(x,1)", 3 ), 2, AST__BAD );
      /* C keeps the second value of a Gaussian pair in variables shared by
         every MathMap, which an active context then takes; an even number of
         values from a new MathMap leaves none kept. */
      printf( "-- gauss pair\n" );
      draws( (AstMapping *) random1( "gauss(0,1)", 1 ), 2, 0.0 );
      printf( "-- mixed\n" );
      draws( (AstMapping *) random1( "rand(0,1)+gauss(0,1)*poisson(5)", 8 ), 6, 0.0 );
   }

   /* A Seed set again restarts the sequence; a copy continues it. */
   head( "random_copies" );
   {
      AstMathMap *m = random1( "rand(0,1)", 42 );
      printf( "-- first\n" );
      draws( (AstMapping *) m, 3, 0.0 );
      AstMathMap *c = astCopy( m );
      printf( "-- copy\n" );
      draws( (AstMapping *) c, 3, 0.0 );
      printf( "-- original\n" );
      draws( (AstMapping *) m, 3, 0.0 );
      astSetI( m, "Seed", 42 );
      printf( "-- reseeded\n" );
      draws( (AstMapping *) m, 3, 0.0 );
      AstMathMap *g = random1( "gauss(0,1)", 42 );
      printf( "-- gauss first\n" );
      draws( (AstMapping *) g, 1, 0.0 );
      AstMathMap *gc = astCopy( g );
      printf( "-- gauss original\n" );
      draws( (AstMapping *) g, 3, 0.0 );
      printf( "-- gauss copy\n" );
      draws( (AstMapping *) gc, 3, 0.0 );
   }

   /* Gaussian values from two MathMaps used in turn. */
   head( "gauss_two_mathmaps" );
   {
      AstMathMap *a = random1( "gauss(0,1)", 5 );
      AstMathMap *b = random1( "gauss(0,1)", 6 );
      printf( "-- a\n" );
      draws( (AstMapping *) a, 1, 0.0 );
      printf( "-- b\n" );
      draws( (AstMapping *) b, 1, 0.0 );
      printf( "-- a\n" );
      draws( (AstMapping *) a, 1, 0.0 );
      printf( "-- b\n" );
      draws( (AstMapping *) b, 1, 0.0 );
      AstMathMap *a2 = random1( "gauss(0,1)", 5 );
      printf( "-- a alone\n" );
      draws( (AstMapping *) a2, 2, 0.0 );
      AstMathMap *b2 = random1( "gauss(0,1)", 6 );
      printf( "-- b alone\n" );
      draws( (AstMapping *) b2, 2, 0.0 );
   }

   /* Attribute strings. */
   head( "attributes" );
   {
      const char *f[] = { "y = x", NULL };
      const char *i[] = { "x = y", NULL };
      AstMathMap *m = astMathMap( 1, 1, 1, f, 1, i, " " );
      attrs( m );
      astSet( m, "SimpFI=5, SimpIF=0, Seed=-12" );
      attrs( m );
      astSet( m, "SimpFI=0, SimpIF=-3" );
      attrs( m );
      astClear( m, "SimpFI, SimpIF, Seed" );
      attrs( m );
      AstZoomMap *z = astZoomMap( 1, 2.0, " " );
      AstCmpMap *cm = astCmpMap( m, z, 1, " " );
      printf( "-- shared\n" );
      astSetI( m, "Seed", 3 );
      failed();
      astSetI( m, "SimpFI", 1 );
      failed();
      astClear( m, "SimpIF" );
      failed();
      cm = astAnnul( cm );
   }

   /* Dumps. */
   {
      const char *f[] = { "R = sqrt( X*X + Y*Y )", "Theta = atan2( Y, X )",
                          "Rout = R * ( 1 + 0.1 * R*R ) + gauss( 0, 0.001 )",
                          "Tout = Theta + rand( -1.0E-6, 1.0E-6 )", NULL };
      const char *i[] = { "X = Rout * cos( Tout )", "Y = Rout * sin( Tout )",
                          NULL };
      AstMathMap *m = astMathMap( 2, 2, 4, f, 2, i, " " );
      dumps( "dump_default", (AstObject *) m );
      astSet( m, "SimpFI=1" );
      dumps( "dump_simpfi", (AstObject *) m );
      astSet( m, "SimpIF=0, Seed=1234" );
      dumps( "dump_all_set", (AstObject *) m );
      astClear( m, "SimpFI, SimpIF, Seed" );
      dumps( "dump_cleared", (AstObject *) m );
      AstMathMap *mi = astCopy( m );
      astSet( mi, "Seed=-5, SimpIF=1" );
      astInvert( mi );
      dumps( "dump_inverted", (AstObject *) mi );
      const char *uf[] = { "y", NULL };
      const char *ui[] = { "t = y * 2", "x = t + 1", NULL };
      AstMathMap *u = astMathMap( 1, 1, 1, uf, 2, ui, " " );
      dumps( "dump_no_forward", (AstObject *) u );
      AstZoomMap *z = astZoomMap( 2, 3.0, " " );
      AstCmpMap *cm = astCmpMap( z, mi, 1, " " );
      dumps( "dump_in_cmpmap", (AstObject *) cm );
   }

   /* Transformations of a MathMap read back with its Seed. */
   head( "seed_read_back" );
   {
      AstMathMap *m = random1( "rand(0,1)", 77 );
      AstFitsChan *fc = astFitsChan( NULL, NULL, "Encoding=NATIVE" );
      astWrite( fc, m );
      astClear( fc, "Card" );
      AstMapping *back = astRead( fc );
      printf( "Seed %s test %d\n", astGetC( back, "Seed" ), astTest( back, "Seed" ) );
      draws( back, 3, 0.0 );
      draws( (AstMapping *) m, 3, 0.0 );
   }

   /* Simplifying a CmpMap whose components allow a restricted simplification
      clears that flag on the components it shares with the input
      (simplify/cap_restricted_component_flags.map). */
   {
      const char *text =
      " Begin CmpMap \t# Compound Mapping\n"
      "    Nin = 1 \t# Number of input coordinates\n"
      "    ReSimp = 1 \t# Simplify restricted to allowed Mappings\n"
      " IsA Mapping \t# Mapping between coordinate systems\n"
      "    MapA = \t# First component Mapping\n"
      "       Begin CmpMap \t# Compound Mapping\n"
      "          Nin = 1 \t# Number of input coordinates\n"
      "       IsA Mapping \t# Mapping between coordinate systems\n"
      "          MapA = \t# First component Mapping\n"
      "             Begin ZoomMap \t# Zoom about the origin\n"
      "                Nin = 1 \t# Number of input coordinates\n"
      "                AlSimp = 1 \t# Will be used by restricted Simplify\n"
      "             IsA Mapping \t# Mapping between coordinate systems\n"
      "                Zoom = 2 \t# Zoom factor\n"
      "             End ZoomMap\n"
      "          MapB = \t# Second component Mapping\n"
      "             Begin ZoomMap \t# Zoom about the origin\n"
      "                Nin = 1 \t# Number of input coordinates\n"
      "                AlSimp = 1 \t# Will be used by restricted Simplify\n"
      "             IsA Mapping \t# Mapping between coordinate systems\n"
      "                Zoom = 3 \t# Zoom factor\n"
      "             End ZoomMap\n"
      "       End CmpMap\n"
      "    MapB = \t# Second component Mapping\n"
      "       Begin CmpMap \t# Compound Mapping\n"
      "          Nin = 1 \t# Number of input coordinates\n"
      "       IsA Mapping \t# Mapping between coordinate systems\n"
      "          MapA = \t# First component Mapping\n"
      "             Begin MathMap \t# Transformation using mathematical functions\n"
      "                Nin = 1 \t# Number of input coordinates\n"
      "             IsA Mapping \t# Mapping between coordinate systems\n"
      "                Fwd1 = \"y=2*x\" \t# Forward function 1\n"
      "                Inv1 = \"x=0.5*y\" \t# Inverse function 1\n"
      "             End MathMap\n"
      "          MapB = \t# Second component Mapping\n"
      "             Begin ZoomMap \t# Zoom about the origin\n"
      "                Nin = 1 \t# Number of input coordinates\n"
      "                AlSimp = 1 \t# Will be used by restricted Simplify\n"
      "             IsA Mapping \t# Mapping between coordinate systems\n"
      "                Zoom = 7 \t# Zoom factor\n"
      "             End ZoomMap\n"
      "       End CmpMap\n"
      " End CmpMap\n";
      AstMapping *m = astFromString( text );
      AstMapping *s = astSimplify( m );
      head( "simplify_input_after" );
      astShow( m );
      s = astAnnul( s );
   }

   astEnd;
   printf( "== end status %d\n", status );
   return 0;
}
