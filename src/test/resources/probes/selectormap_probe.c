/* selectormap_probe -- SelectorMap behaviour of the pinned C library that
 * no fixture or oracle holds: the index (or bad value) a SelectorMap gives
 * for points in overlapping, negated, unbounded and open Regions, on a
 * boundary and bad on one axis; the dump of a SelectorMap before and after
 * a transformation (selectormap.c Transform negates and re-closes its own
 * Regions in place, leaving Negated and Closed set), a copy of it and its
 * comparison with a new one; simplification of a SelectorMap whose Regions
 * do not simplify and of one whose Region does; two SelectorMaps in series,
 * 1-D both forward, and 1-D and 2-D with one inverted; the load of a dump
 * whose first Region's FrameSet has a Mapping that is not a UnitMap (the
 * later Regions, written without their Frame, are given that FrameSet);
 * the load of a dump without Regions; testregions.c's
 * checkSelectorMapEqual; and the constructor's errors.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o selectormap_probe
 *      selectormap_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./selectormap_probe > selectormap_probe.txt 2>&1; the output,
 * with trailing white space removed from every line, is stored as the
 * "output" string of selectormap_probe.json, since the dumps hold C's item
 * names, which the spelling check rejects. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

/* The lines a Channel writes, which a second Channel reads back. */
#define MAXLINES 2000
static char *lines[ MAXLINES ];
static int nline = 0;
static int iline = 0;

static void sink( const char *line ) {
   if( nline < MAXLINES ) lines[ nline++ ] = strcpy( malloc( strlen( line ) + 1 ), line );
}

static const char *source( void ) {
   return ( iline < nline ) ? lines[ iline++ ] : NULL;
}

static void clear_lines( void ) {
   for( int i = 0; i < nline; i++ ) free( lines[ i ] );
   nline = iline = 0;
}

/* A dump given as text, read through a Channel. */
static const char **text_lines;
static int text_line;

static const char *text_source( void ) {
   return text_lines[ text_line ] ? text_lines[ text_line++ ] : NULL;
}

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

static AstBox *box2( double xlo, double ylo, double xhi, double yhi ) {
   double lb[ 2 ], ub[ 2 ];
   lb[ 0 ] = xlo;
   lb[ 1 ] = ylo;
   ub[ 0 ] = xhi;
   ub[ 1 ] = yhi;
   return astBox( astFrame( 2, " " ), 1, lb, ub, NULL, " " );
}

static AstBox *box1( double lo, double hi ) {
   return astBox( astFrame( 1, " " ), 1, &lo, &hi, NULL, " " );
}

static AstSelectorMap *selmap( int n, AstRegion **regs, double badval ) {
   return astSelectorMap( n, (void **) regs, badval, " " );
}

/* The points of the 2-D cases. */
#define NPT2 15
static const double px[ NPT2 ] = { 2.0, 7.0, 12.0, 10.0, 15.0, 60.0, -100.0, -200.0,
                                   50.0, -105.0, AST__BAD, 5.0, AST__BAD, 20.0, 25.0 };
static const double py[ NPT2 ] = { 2.0, 7.0, 12.0, 5.0, 15.0, -1000.0, -100.0, 0.0,
                                   0.0, -100.0, 5.0, AST__BAD, AST__BAD, 25.0, 25.0 };

/* The forward transformation of the 2-D points, one line each. */
static void tran2( AstMapping *m ) {
   double in[ 2 ][ NPT2 ], out[ NPT2 ];
   for( int i = 0; i < NPT2; i++ ) {
      in[ 0 ][ i ] = px[ i ];
      in[ 1 ][ i ] = py[ i ];
   }
   astTranN( m, NPT2, 2, NPT2, (const double *) in, 1, 1, NPT2, out );
   if( failed() ) return;
   for( int i = 0; i < NPT2; i++ ) {
      printf( "fwd %.17g %.17g -> %.17g\n", px[ i ], py[ i ], out[ i ] );
   }
}

/* The forward transformation of 1-D points, one line each. */
static void tran1( AstMapping *m, int n, const double *in ) {
   double out[ 20 ];
   astTran1( m, n, in, 1, out );
   if( failed() ) return;
   for( int i = 0; i < n; i++ ) {
      printf( "fwd %.17g -> %.17g\n", in[ i ], out[ i ] );
   }
}

/* The class of the simplified Mapping, whether it is the Mapping itself,
   and its dump. */
static void simp( AstMapping *m ) {
   AstMapping *s = astSimplify( m );
   if( failed() ) return;
   printf( "-- simplified %s same %d\n", astGetC( s, "Class" ), astSame( s, m ) );
   astShow( s );
   failed();
}

/* The SelectorMap of Review Focus 1: Regions that overlap, an unbounded
   Interval and a negated Circle. */
static AstSelectorMap *focus( double badval ) {
   AstRegion *regs[ 4 ];
   double lb[] = { 50.0, AST__BAD }, ub[] = { AST__BAD, AST__BAD };
   double centre[] = { -100.0, -100.0 }, radius[] = { 5.0 };
   regs[ 0 ] = (AstRegion *) box2( 0.0, 0.0, 10.0, 10.0 );
   regs[ 1 ] = (AstRegion *) box2( 5.0, 5.0, 15.0, 15.0 );
   regs[ 2 ] = (AstRegion *) astInterval( astFrame( 2, " " ), lb, ub, NULL, " " );
   regs[ 3 ] = (AstRegion *) astCircle( astFrame( 2, " " ), 1, centre, radius, NULL, " " );
   astNegate( regs[ 3 ] );
   return selmap( 4, regs, badval );
}

/* A SelectorMap of two copies of one Box, the first open. */
static AstSelectorMap *open_closed( void ) {
   AstRegion *regs[ 2 ];
   regs[ 0 ] = (AstRegion *) box2( 20.0, 20.0, 30.0, 30.0 );
   astSetL( regs[ 0 ], "Closed", 0 );
   regs[ 1 ] = (AstRegion *) box2( 20.0, 20.0, 30.0, 30.0 );
   return selmap( 2, regs, -1.0 );
}

int main( void ) {
   AstMapping *sm, *sm2, *cm, *back, *fresh;
   AstRegion *regs[ 2 ];
   AstChannel *ch;
   AstCircle *circle;
   AstMathMap *cube;
   double pts1[] = { -1.0, 0.0, 0.5, 1.0, 2.0, AST__BAD };

   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;

   /* Review Focus 1, with and without a bad value. */
   head( "focus_points" );
   tran2( (AstMapping *) focus( -1.0 ) );
   head( "focus_points_bad" );
   tran2( (AstMapping *) focus( AST__BAD ) );
   head( "open_closed_points" );
   tran2( (AstMapping *) open_closed() );

   /* Review Focus 2: dumps before and after a transformation, a copy
      made after it, and comparisons. */
   head( "focus_dump_before" );
   sm = (AstMapping *) focus( -1.0 );
   astShow( sm );
   failed();
   head( "focus_dump_after" );
   tran2( sm );
   astShow( sm );
   failed();
   head( "focus_copy_after" );
   astShow( astCopy( sm ) );
   failed();
   head( "focus_equal_after" );
   fresh = (AstMapping *) focus( -1.0 );
   printf( "equal fresh %d\n", astEqual( sm, fresh ) );
   printf( "equal fresh reversed %d\n", astEqual( fresh, sm ) );
   printf( "equal copy %d\n", astEqual( sm, astCopy( sm ) ) );
   failed();
   head( "open_closed_dump_after" );
   sm = (AstMapping *) open_closed();
   tran2( sm );
   astShow( sm );
   failed();

   /* Item 3: simplification. */
   head( "simplify_unchanged" );
   regs[ 0 ] = (AstRegion *) box2( 0.0, 0.0, 10.0, 10.0 );
   regs[ 1 ] = (AstRegion *) box2( 20.0, 20.0, 30.0, 30.0 );
   sm = (AstMapping *) selmap( 2, regs, -1.0 );
   simp( sm );
   printf( "-- original after\n" );
   astShow( sm );
   failed();

   head( "simplify_changed" );
   {
      double lb[] = { 0.0, 0.0 }, ub[] = { 10.0, 10.0 };
      regs[ 0 ] = (AstRegion *) astInterval( astFrame( 2, " " ), lb, ub, NULL, " " );
   }
   regs[ 1 ] = (AstRegion *) box2( 20.0, 20.0, 30.0, 30.0 );
   sm = (AstMapping *) selmap( 2, regs, -1.0 );
   astShow( sm );
   simp( sm );
   tran2( astSimplify( sm ) );
   failed();

   /* Item 4: SelectorMaps in series. */
   head( "series_1d_both_forward" );
   regs[ 0 ] = (AstRegion *) box1( 0.0, 1.0 );
   sm = (AstMapping *) selmap( 1, regs, AST__BAD );
   sm2 = astCopy( sm );
   printf( "equal %d\n", astEqual( sm, sm2 ) );
   cm = (AstMapping *) astCmpMap( sm, sm2, 1, " " );
   tran1( cm, 6, pts1 );
   simp( cm );
   tran1( astSimplify( cm ), 6, pts1 );
   failed();

   head( "series_1d_second_inverted" );
   sm2 = astCopy( sm );
   astInvert( sm2 );
   simp( (AstMapping *) astCmpMap( sm, sm2, 1, " " ) );

   head( "series_1d_different" );
   regs[ 0 ] = (AstRegion *) box1( 0.0, 2.0 );
   sm2 = (AstMapping *) selmap( 1, regs, AST__BAD );
   simp( (AstMapping *) astCmpMap( sm, sm2, 1, " " ) );

   head( "series_2d_second_inverted" );
   regs[ 0 ] = (AstRegion *) box2( 0.0, 0.0, 10.0, 10.0 );
   regs[ 1 ] = (AstRegion *) box2( 20.0, 20.0, 30.0, 30.0 );
   sm = (AstMapping *) selmap( 2, regs, -1.0 );
   sm2 = astCopy( sm );
   astInvert( sm2 );
   simp( (AstMapping *) astCmpMap( sm, sm2, 1, " " ) );

   head( "series_2d_first_inverted" );
   simp( (AstMapping *) astCmpMap( sm2, sm, 1, " " ) );

   head( "parallel_2d" );
   simp( (AstMapping *) astCmpMap( sm, sm, 0, " " ) );

   /* The load of a dump whose first Region's FrameSet holds a MathMap,
      which a Circle cannot absorb. */
   head( "load_first_region_mapped" );
   {
      double centre[] = { 0.0, 0.0 }, radius[] = { 1.0 };
      const char *fwd[] = { "x=a*a*a", "y=b" };
      const char *inv[] = { "a=sign(abs(x)**(1/3.),x)", "b=y" };
      circle = astCircle( astFrame( 2, " " ), 1, centre, radius, NULL, " " );
      cube = astMathMap( 2, 2, 2, fwd, 2, inv, " " );
   }
   regs[ 0 ] = (AstRegion *) astMapRegion( circle, (AstMapping *) cube, astFrame( 2, " " ) );
   regs[ 1 ] = (AstRegion *) box2( 3.0, 3.0, 4.0, 4.0 );
   sm = (AstMapping *) selmap( 2, regs, -1.0 );
   clear_lines();
   ch = astChannel( source, sink, " " );
   astWrite( ch, sm );
   back = astRead( ch );
   if( !failed() ) {
      printf( "-- written\n" );
      astShow( sm );
      printf( "-- read back\n" );
      astShow( back );
      printf( "-- equal %d\n", astEqual( sm, back ) );
      double q[ 2 ][ 4 ] = { { 0.5, 3.5, 40.0, 7.0 }, { 0.5, 3.5, 3.5, 7.0 } }, out[ 4 ];
      astTranN( sm, 4, 2, 4, (const double *) q, 1, 1, 4, out );
      for( int i = 0; i < 4; i++ ) printf( "written %.17g %.17g -> %.17g\n", q[ 0 ][ i ], q[ 1 ][ i ], out[ i ] );
      astTranN( back, 4, 2, 4, (const double *) q, 1, 1, 4, out );
      for( int i = 0; i < 4; i++ ) printf( "read back %.17g %.17g -> %.17g\n", q[ 0 ][ i ], q[ 1 ][ i ], out[ i ] );
      failed();
   }

   /* The load of a dump without Regions. */
   head( "load_no_regions" );
   {
      static const char *text[] = {
         " Begin SelectorMap",
         "    Nin = 2",
         "    Nout = 1",
         "    Inv = 0",
         " IsA Mapping",
         "    BadVal = 7",
         " End SelectorMap",
         NULL
      };
      text_lines = text;
      text_line = 0;
      ch = astChannel( text_source, NULL, " " );
      back = astRead( ch );
      if( !failed() && back ) {
         printf( "-- read %s Nin %d Nout %d\n", astGetC( back, "Class" ), astGetI( back, "Nin" ),
                 astGetI( back, "Nout" ) );
         failed();
         tran2( back );
         printf( "-- dump\n" );
         astShow( back );
         failed();
         printf( "-- copy\n" );
         astShow( astCopy( back ) );
         failed();
         printf( "-- equal copy %d\n", astEqual( back, astCopy( back ) ) );
         printf( "-- equal focus %d\n", astEqual( back, focus( 7.0 ) ) );
         failed();
         simp( back );
      } else {
         printf( "-- read nothing\n" );
      }
   }

   /* testregions.c checkSelectorMapEqual. */
   head( "check_selectormap_equal" );
   {
      double c1[] = { 0.0, 0.0 }, c2[] = { 5.0, 5.0 }, r[] = { 1.0 };
      AstFrame *frm = astFrame( 2, " " );
      AstSelectorMap *one, *two;
      regs[ 0 ] = (AstRegion *) astCircle( frm, 1, c1, r, NULL, " " );
      regs[ 1 ] = (AstRegion *) astCircle( frm, 1, c2, r, NULL, " " );
      one = selmap( 1, regs, AST__BAD );
      two = selmap( 2, regs, AST__BAD );
      printf( "test 1 %d\n", astEqual( one, two ) );
      printf( "test 2 %d\n", astEqual( two, one ) );
      printf( "test 3 %d\n", astEqual( two, astCopy( two ) ) );
      failed();
   }

   /* The constructor's errors. */
   head( "error_no_regions" );
   selmap( 0, regs, AST__BAD );
   failed();
   head( "error_naxes" );
   regs[ 0 ] = (AstRegion *) box2( 0.0, 0.0, 1.0, 1.0 );
   regs[ 1 ] = (AstRegion *) box1( 0.0, 1.0 );
   selmap( 2, regs, AST__BAD );
   failed();
   head( "error_skyframe" );
   {
      double centre[] = { 0.0, 0.0 }, radius[] = { 0.1 };
      AstRegion *r3[ 3 ];
      r3[ 0 ] = (AstRegion *) box2( 0.0, 0.0, 1.0, 1.0 );
      r3[ 1 ] = (AstRegion *) box2( 2.0, 2.0, 3.0, 3.0 );
      r3[ 2 ] = (AstRegion *) astCircle( astSkyFrame( " " ), 1, centre, radius, NULL, " " );
      selmap( 3, r3, AST__BAD );
      failed();
   }

   astEnd;
   return 0;
}
