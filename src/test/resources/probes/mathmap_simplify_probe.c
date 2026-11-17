/* mathmap_simplify_probe -- MathMap simplification, equality and the
 * MathMaps of units conversions in the pinned C library: a MathMap in
 * series with its inverse for each setting of SimpFI and SimpIF, two
 * MathMaps with equal and unequal functions, astEqual on pairs of
 * MathMaps, the Mapping astUnitMapper makes for conversions that need a
 * MathMap, with the label it gives, and a FluxFrame conversion to a
 * logarithmic Unit.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -I<build> -o mathmap_simplify_probe mathmap_simplify_probe.c
 *      <build>/libast.a <build>/libast_pal.a <build>/libast_err.a
 *      <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run on macOS as ./mathmap_simplify_probe > mathmap_simplify_probe.out
 * 2>&1, then trailing white space removed from every line. */
#include <stdio.h>
#include <string.h>
#include "ast.h"

/* astUnitMapper is protected; its prototype is in unit.h. */
AstMapping *astUnitMapper_( const char *, const char *, const char *,
                            char **, int * );

static void head( const char *name ) {
   printf( "== %s\n", name );
}

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
   with the attribute settings given. */
static AstMathMap *make( int nin, int nout, const char **fwd,
                         const char **inv, const char *settings ) {
   int nfwd = 0, ninv = 0;
   while( fwd[ nfwd ] ) nfwd++;
   while( inv[ ninv ] ) ninv++;
   AstMathMap *m = astMathMap( nin, nout, nfwd, fwd, ninv, inv, "%s",
                               settings );
   if( failed() ) printf( "failed to make %s\n", fwd[ 0 ] );
   return m;
}

/* The class, input and output counts of the simplified Mapping, then its
   dump. */
static void simplified( const char *name, AstMapping *map ) {
   head( name );
   AstMapping *s = astSimplify( map );
   if( failed() ) return;
   printf( "%s %d %d\n", astGetC( s, "Class" ), astGetI( s, "Nin" ),
           astGetI( s, "Nout" ) );
   astShow( s );
   s = astAnnul( s );
}

/* The settings of SimpFI or SimpIF probed: unset, 0 and 1. */
static const char *flag_settings[] = { "", "=0", "=1" };
static const char *flag_names[] = { "unset", "0", "1" };

/* The Mapping astUnitMapper gives for the units "in" to "out", with the
   label "Frequency", its forward and inverse images of the values given,
   and the label. */
static void units( const char *name, const char *in, const char *out,
                   int nval, const double *vals ) {
   char *outlab = NULL;
   head( name );
   AstMapping *m = astUnitMapper_( in, out, "Frequency", &outlab,
                                   astGetStatusPtr );
   if( failed() ) return;
   if( m ) m = astMakeId( m );
   if( !m ) {
      printf( "no conversion\n" );
      return;
   }
   printf( "label %s\n", outlab ? outlab : "(null)" );
   astShow( m );
   double fwd[ 10 ], inv[ 10 ];
   astTran1( m, nval, vals, 1, fwd );
   astTran1( m, nval, fwd, 0, inv );
   if( failed() ) return;
   for( int i = 0; i < nval; i++ ) {
      printf( "%.17g -> %.17g -> %.17g\n", vals[ i ], fwd[ i ], inv[ i ] );
   }
   outlab = astFree( outlab );
   m = astAnnul( m );
}

int main( void ) {
   int status = 0;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;

   /* A MathMap in series with an inverted copy of itself, each way round,
      for each setting of SimpFI and SimpIF. */
   {
      const char *f[] = { "y = 2 * x", NULL };
      const char *i[] = { "x = y / 2", NULL };
      for( int fi = 0; fi < 3; fi++ ) {
         for( int jf = 0; jf < 3; jf++ ) {
            char settings[ 100 ] = "";
            if( fi ) sprintf( settings + strlen( settings ), "SimpFI%s",
                              flag_settings[ fi ] );
            if( fi && jf ) strcat( settings, "," );
            if( jf ) sprintf( settings + strlen( settings ), "SimpIF%s",
                              flag_settings[ jf ] );
            AstMathMap *m = make( 1, 1, f, i, settings );
            AstMathMap *mi = astCopy( m );
            astInvert( mi );
            char name[ 100 ];
            sprintf( name, "fwd_inv_simpfi_%s_simpif_%s", flag_names[ fi ],
                     flag_names[ jf ] );
            simplified( name, (AstMapping *) astCmpMap( m, mi, 1, " " ) );
            sprintf( name, "inv_fwd_simpfi_%s_simpif_%s", flag_names[ fi ],
                     flag_names[ jf ] );
            simplified( name, (AstMapping *) astCmpMap( mi, m, 1, " " ) );
         }
      }
   }

   /* Both MathMaps must consent: the second has SimpFI cleared. */
   {
      const char *f[] = { "y = 2 * x", NULL };
      const char *i[] = { "x = y / 2", NULL };
      AstMathMap *m = make( 1, 1, f, i, "SimpFI=1,SimpIF=1" );
      AstMathMap *mi = astCopy( m );
      astClear( mi, "SimpFI" );
      astInvert( mi );
      simplified( "consent_of_both", (AstMapping *) astCmpMap( m, mi, 1, " " ) );
   }

   /* Two MathMaps made separately: functions that differ only in case and
      white space are equal once cleaned; functions that are equivalent but
      written differently are not. */
   {
      const char *f1[] = { "Y = 2 * X", NULL };
      const char *i1[] = { "X = Y / 2", NULL };
      const char *f2[] = { "y=2*x", NULL };
      const char *i2[] = { "x=y/2", NULL };
      const char *f3[] = { "y = x * 2", NULL };
      const char *i3[] = { "x = y / 2", NULL };
      AstMathMap *a = make( 1, 1, f1, i1, "SimpFI=1,SimpIF=1" );
      AstMathMap *b = make( 1, 1, f2, i2, "SimpFI=1,SimpIF=1" );
      AstMathMap *c = make( 1, 1, f3, i3, "SimpFI=1,SimpIF=1" );
      astInvert( b );
      astInvert( c );
      simplified( "equal_functions", (AstMapping *) astCmpMap( a, b, 1, " " ) );
      simplified( "unequal_functions", (AstMapping *) astCmpMap( a, c, 1, " " ) );
   }

   /* Different numbers of inputs and outputs, and intermediate functions. */
   {
      const char *f[] = { "r = sqrt( x * x + y * y )", NULL };
      const char *i[] = { "x = r", "y = 0", NULL };
      AstMathMap *m = make( 2, 1, f, i, "SimpFI=1,SimpIF=1" );
      AstMathMap *mi = astCopy( m );
      astInvert( mi );
      simplified( "nin_nout_fwd_inv", (AstMapping *) astCmpMap( m, mi, 1, " " ) );
      simplified( "nin_nout_inv_fwd", (AstMapping *) astCmpMap( mi, m, 1, " " ) );

      const char *f2[] = { "t = x + 1", "u = y * t", "v = x - y", NULL };
      const char *i2[] = { "x = u + v", "y = u - v", NULL };
      AstMathMap *n = make( 2, 2, f2, i2, "SimpFI=1,SimpIF=1" );
      AstMathMap *ni = astCopy( n );
      astInvert( ni );
      simplified( "intermediate_functions", (AstMapping *) astCmpMap( n, ni, 1, " " ) );

      /* The pair cancels within a longer series, and in parallel it does not. */
      AstZoomMap *z = astZoomMap( 2, 3.0, " " );
      AstCmpMap *pair = astCmpMap( n, ni, 1, " " );
      simplified( "pair_then_zoommap", (AstMapping *) astCmpMap( pair, z, 1, " " ) );
      simplified( "pair_in_parallel", (AstMapping *) astCmpMap( n, ni, 0, " " ) );

      /* The same MathMap twice, not inverted. */
      simplified( "same_twice", (AstMapping *) astCmpMap( n, n, 1, " " ) );
   }

   /* An outer parallel CmpMap whose first component is an inverted series
      CmpMap of two identical MathMaps. */
   {
      const char *f[] = { "y=2*x", NULL };
      const char *i[] = { "x=0.5*y", NULL };
      AstMathMap *a = make( 1, 1, f, i, "SimpFI=1,SimpIF=1" );
      AstMathMap *b = astCopy( a );
      AstCmpMap *first = astCmpMap( a, b, 1, " " );
      astInvert( first );
      AstMathMap *c = make( 1, 1, f, i, "SimpFI=1,SimpIF=1" );
      AstMathMap *d = astCopy( c );
      AstCmpMap *second = astCmpMap( c, d, 1, " " );
      simplified( "parallel_with_inverted_series", (AstMapping *) astCmpMap( first, second, 0, " " ) );
   }

   /* astEqual on pairs of MathMaps. */
   head( "equal" );
   {
      const char *fa[] = { "y = 2 * x", NULL };
      const char *ia[] = { "x = y / 2", NULL };
      const char *fb[] = { "y = 3 * x", NULL };
      const char *ib[] = { "x = y / 3", NULL };
      const char *fc[] = { "y = x + 1", NULL };
      const char *ic[] = { "x = y - 1", NULL };
      const char *fd[] = { "y = x - 1", NULL };
      const char *id[] = { "x = y + 1", NULL };
      const char *fe[] = { "y = x", NULL };
      const char *ie[] = { "x = y", NULL };
      const char *ff[] = { "b = a", NULL };
      const char *iff[] = { "a = b", NULL };
      const char *fg[] = { "y = max( x, 1 )", NULL };
      const char *fh[] = { "y = min( x, 1 )", NULL };
      const char *fi[] = { "y = max( x, 1, 2 )", NULL };
      const char *fj[] = { "y = max( x, 1, 3 )", NULL };
      const char *iu[] = { "x", NULL };
      const char *fu[] = { "y", NULL };
      const char *fk[] = { "y = x * 2", NULL };
      const char *f2[] = { "z = x", NULL };
      const char *f3[] = { "z = y", NULL };
      const char *i23[] = { "x = z", "y = z", NULL };
      const char *f4[] = { "y = 2 * x", "w = y", NULL };
      const char *i4[] = { "x = w / 2", NULL };
      const char *f5[] = { "y = x * x", NULL };
      const char *i5[] = { "x = sqrt( y )", NULL };
      const char *f6[] = { "y = x * x * x", NULL };

      AstMathMap *a = make( 1, 1, fa, ia, " " );
      AstMathMap *a2 = make( 1, 1, fa, ia, "SimpFI=1" );
      AstMathMap *ainv = astCopy( a );
      astInvert( ainv );
      AstMathMap *ainv2 = astCopy( a );
      astInvert( ainv2 );
      AstMathMap *b = make( 1, 1, fb, ib, " " );
      AstMathMap *c = make( 1, 1, fc, ic, " " );
      AstMathMap *d = make( 1, 1, fd, id, " " );
      AstMathMap *e = make( 1, 1, fe, ie, " " );
      AstMathMap *f = make( 1, 1, ff, iff, " " );
      AstMathMap *g = make( 1, 1, fg, iu, " " );
      AstMathMap *h = make( 1, 1, fh, iu, " " );
      AstMathMap *i = make( 1, 1, fi, iu, " " );
      AstMathMap *j = make( 1, 1, fj, iu, " " );
      AstMathMap *u1 = make( 1, 1, fu, ia, " " );
      AstMathMap *u2 = make( 1, 1, fu, ia, " " );
      AstMathMap *k = make( 1, 1, fk, ia, " " );
      AstMathMap *m2 = make( 2, 1, f2, i23, " " );
      AstMathMap *m3 = make( 2, 1, f3, i23, " " );
      AstMathMap *m4 = make( 1, 1, f4, i4, " " );
      AstMathMap *m5 = make( 1, 1, f5, i5, " " );
      AstMathMap *m6 = make( 1, 1, f6, i5, " " );
      AstZoomMap *z = astZoomMap( 1, 2.0, " " );
      AstMathMap *u3 = make( 1, 1, fu, iu, " " );
      AstMathMap *u4 = make( 1, 1, fu, iu, " " );

      printf( "same_object %d\n", astEqual( a, a ) );
      printf( "same_functions %d\n", astEqual( a, a2 ) );
      printf( "different_constant %d\n", astEqual( a, b ) );
      printf( "last_opcode_differs %d\n", astEqual( c, d ) );
      printf( "inverted_copy %d\n", astEqual( a, ainv ) );
      printf( "both_inverted %d\n", astEqual( ainv, ainv2 ) );
      printf( "renamed_variables %d\n", astEqual( e, f ) );
      printf( "max_min %d\n", astEqual( g, h ) );
      printf( "max_constant %d\n", astEqual( i, j ) );
      printf( "max_argument_count %d\n", astEqual( g, i ) );
      printf( "no_forward_both %d\n", astEqual( u1, u2 ) );
      printf( "no_forward_one %d\n", astEqual( u1, a ) );
      printf( "forward_one %d\n", astEqual( a, u1 ) );
      printf( "neither_direction %d\n", astEqual( u3, u4 ) );
      printf( "operand_order %d\n", astEqual( a, k ) );
      printf( "variable_index %d\n", astEqual( m2, m3 ) );
      printf( "nin_differs %d\n", astEqual( a, m2 ) );
      printf( "function_count_differs %d\n", astEqual( a, m4 ) );
      printf( "opcode_count_differs %d\n", astEqual( m5, m6 ) );
      printf( "zoommap %d\n", astEqual( a, z ) );
      failed();
   }

   /* The Mappings of units conversions that need a MathMap. */
   {
      const double v1[] = { 0.0, 2.0, -1.5 };
      const double v2[] = { 2.0, 0.25, 100.0 };
      units( "units_log_hz_to_hz", "log(Hz)", "Hz", 3, v1 );
      units( "units_sqrt_hz_to_hz", "sqrt(Hz)", "Hz", 3, v2 );
      units( "units_log_kms_to_ms", "log(km/s)", "m/s", 3, v1 );
      units( "units_hz_to_log_hz", "Hz", "log(Hz)", 3, v2 );
      units( "units_hz_to_sqrt_hz", "Hz", "sqrt(Hz)", 3, v2 );
      units( "units_ln_hz_to_mhz", "ln(Hz)", "MHz", 3, v1 );
      units( "units_exp_hz_to_hz", "exp(Hz)", "Hz", 3, v2 );
      units( "units_log_mhz_to_log_hz", "log(MHz)", "log(Hz)", 3, v1 );
      units( "units_hz_to_per_hz", "Hz", "1/Hz", 3, v2 );
      units( "units_hz15_to_hz", "Hz**1.5", "Hz", 3, v2 );
      units( "units_m2_to_cm", "m**2", "cm", 3, v2 );
      units( "units_log_jy_to_w", "log(Jy)", "W/m**2/Hz", 3, v1 );
   }

   /* A conversion and its reverse in series do not cancel: their
      functions are not each other's inverse functions as text. */
   {
      AstMapping *m1 = astMakeId( astUnitMapper_( "log(Hz)", "Hz", NULL, NULL,
                                                  astGetStatusPtr ) );
      AstMapping *m2 = astMakeId( astUnitMapper_( "Hz", "log(Hz)", NULL, NULL,
                                                  astGetStatusPtr ) );
      simplified( "units_there_and_back", (AstMapping *) astCmpMap( m1, m2, 1, " " ) );
      AstMapping *m3 = astCopy( m1 );
      astInvert( m3 );
      simplified( "units_with_inverse", (AstMapping *) astCmpMap( m1, m3, 1, " " ) );
   }

   /* A FluxFrame in Jy converted to one in a logarithmic Unit. */
   head( "flux_log_conversion" );
   {
      AstFluxFrame *jy = astFluxFrame( AST__BAD, NULL, "Unit=Jy" );
      AstFluxFrame *lg = astFluxFrame( AST__BAD, NULL, "Unit=log(W/m^2/Hz)" );
      AstFrameSet *fs = astConvert( jy, lg, "" );
      if( !failed() && fs ) {
         AstMapping *m = astGetMapping( fs, AST__BASE, AST__CURRENT );
         AstMapping *s = astSimplify( m );
         printf( "%s\n", astGetC( s, "Class" ) );
         astShow( s );
         const double vals[] = { 1.0, 1000.0, 0.5 };
         double fwd[ 3 ], inv[ 3 ];
         astTran1( fs, 3, vals, 1, fwd );
         astTran1( fs, 3, fwd, 0, inv );
         failed();
         for( int i = 0; i < 3; i++ ) {
            printf( "%.17g -> %.17g -> %.17g\n", vals[ i ], fwd[ i ], inv[ i ] );
         }
      } else {
         printf( "no conversion\n" );
      }
   }

   astEnd;
   printf( "== end status %d\n", status );
   return 0;
}
