/* stc_probe -- StcResourceProfile behaviour of the pinned C library that the
 * region_stc oracle does not hold:
 *   - an Stc used (transformed, or asked whether it is bounded), then dumped,
 *     copied and compared: stc.c's Transform and GetBounded set the
 *     encapsulated Region's Negated to its own value around the call and
 *     leave it set, so that Negated then tests as set on the Stc;
 *   - AstroCoords elements without keys, with a Value only, and with a
 *     Region in another Frame (a SpecFrame in MHz for an Stc in GHz), as
 *     astGetStcCoord gives them; astGetStcCoord's index errors; and
 *     astInitStc's errors for each kind of bad AstroCoords KeyMap;
 *   - Frame attributes, Negated, Closed, MeshSize and FillFactor set on and
 *     cleared from an Stc, Adaptive and not, as the dump, astGetStcRegion
 *     and astGetStcCoord then show them, and the read-only RegionClass;
 *   - testregions.c's checkStcNegated;
 *   - dumps edited by hand and read: "Begin Stc", the abstract class, which
 *     C reads; an Stc without its Region; an Stc with fewer Coord<n> items
 *     than Ncoord; and Ncoord below zero.
 *
 * Each case starts with a line "== <name>"; error reports, which the error
 * module writes to standard error, are merged in order (stdout unbuffered).
 * A dump read back is printed first, between "-- text" and "-- end text".
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o stc_probe
 *      stc_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./stc_probe > stc_probe.txt 2>&1; the output, with trailing
 * white space removed from every line, is stored as the "output" string of
 * stc_probe.json, since the dumps hold C's item names, which the spelling
 * check rejects. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

/* The lines a Channel writes. */
#define MAXLINES 4000
static char *lines[ MAXLINES ];
static int nline = 0;

static void sink( const char *line ) {
   if( nline < MAXLINES ) lines[ nline++ ] = strcpy( malloc( strlen( line ) + 1 ), line );
}

static void clear_lines( void ) {
   for( int i = 0; i < nline; i++ ) free( lines[ i ] );
   nline = 0;
}

/* Lines to be read, NULL-terminated. */
static const char *text[ MAXLINES + 1 ];
static int text_line;

static const char *text_source( void ) {
   return text[ text_line ] ? text[ text_line++ ] : NULL;
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

/* The dump of "obj" into "lines". */
static void dump_lines( AstObject *obj ) {
   AstChannel *ch;
   clear_lines();
   ch = astChannel( NULL, sink, " " );
   astWrite( ch, obj );
   ch = astAnnul( ch );
}

/* Prints the text in "text", then reads it, printing the class read. */
static AstObject *read_text( void ) {
   AstChannel *ch;
   AstObject *obj;
   printf( "-- text\n" );
   for( int i = 0; text[ i ]; i++ ) printf( "%s\n", text[ i ] );
   printf( "-- end text\n" );
   text_line = 0;
   ch = astChannel( text_source, NULL, " " );
   obj = astRead( ch );
   ch = astAnnul( ch );
   if( failed() || !obj ) {
      printf( "-- read nothing\n" );
      return NULL;
   }
   printf( "-- read %s\n", astGetC( obj, "Class" ) );
   return obj;
}

/* The two test points of a 2-D Stc transformed, one line each. */
static void tran2( AstObject *stc, double x0, double y0, double x1, double y1 ) {
   double xin[ 2 ], yin[ 2 ], xout[ 2 ], yout[ 2 ];
   xin[ 0 ] = x0;
   yin[ 0 ] = y0;
   xin[ 1 ] = x1;
   yin[ 1 ] = y1;
   astTran2( stc, 2, xin, yin, 1, xout, yout );
   if( failed() ) return;
   for( int i = 0; i < 2; i++ ) {
      printf( "tran %.17g %.17g -> %.17g %.17g\n", xin[ i ], yin[ i ], xout[ i ], yout[ i ] );
   }
}

static AstCircle *circle2( double x, double y, double r ) {
   double c[ 2 ];
   c[ 0 ] = x;
   c[ 1 ] = y;
   return astCircle( astFrame( 2, " " ), 1, c, &r, NULL, " " );
}

static AstStcResourceProfile *plain( void ) {
   return astStcResourceProfile( circle2( 0.0, 0.0, 1.0 ), 0, NULL, " " );
}

/* An Interval from "lo" to "hi" in a SpecFrame of frequency in "unit". */
static AstInterval *spec_interval( const char *unit, double lo, double hi ) {
   AstSpecFrame *sf = astSpecFrame( "System=FREQ" );
   astSet( sf, "Unit=%s", unit );
   return astInterval( sf, &lo, &hi, NULL, " " );
}

/* Prints astGetStcCoord( stc, i ) as a dump. */
static void show_coord( AstStc *stc, int i ) {
   AstKeyMap *km = astGetStcCoord( stc, i );
   if( failed() ) return;
   printf( "-- coord %d\n", i );
   astShow( km );
   failed();
}

/* Prints the Negated, Closed, MeshSize and FillFactor values and whether
   each is set. */
static void show_attrs( const char *what, AstObject *obj ) {
   printf( "%s Negated %d %d Closed %d %d MeshSize %d %d FillFactor %.17g %d\n", what,
           astGetI( obj, "Negated" ), astTest( obj, "Negated" ),
           astGetI( obj, "Closed" ), astTest( obj, "Closed" ),
           astGetI( obj, "MeshSize" ), astTest( obj, "MeshSize" ),
           astGetD( obj, "FillFactor" ), astTest( obj, "FillFactor" ) );
   failed();
}

/* An Stc made with one AstroCoords KeyMap, or the status. */
static void make_with( AstRegion *reg, AstKeyMap *km ) {
   AstStcResourceProfile *stc = astStcResourceProfile( reg, 1, &km, " " );
   if( !failed() ) printf( "made %s\n", astGetC( stc, "Class" ) );
}

int main( void ) {
   AstStcResourceProfile *stc, *stc2, *copy;
   AstKeyMap *km, *km2, *kms[ 2 ];
   AstRegion *reg;
   AstObject *obj;
   AstSkyFrame *sky;
   int i, j;

   setvbuf( stdout, NULL, _IONBF, 0 );
   astBegin;

   /* Review Focus 2: an Stc used, then dumped, copied and compared. */
   head( "used_plain" );
   stc = plain();
   printf( "test Negated before %d\n", astTest( stc, "Negated" ) );
   tran2( (AstObject *) stc, 0.0, 0.0, 5.0, 0.0 );
   printf( "test Negated after %d\n", astTest( stc, "Negated" ) );
   astShow( stc );
   copy = astCopy( stc );
   printf( "copy test Negated %d\n", astTest( copy, "Negated" ) );
   printf( "equal copy %d\n", astEqual( stc, copy ) );
   printf( "equal fresh %d\n", astEqual( stc, plain() ) );
   printf( "equal fresh reversed %d\n", astEqual( plain(), stc ) );
   failed();

   head( "used_negated" );
   stc = astCopy( plain() );
   astNegate( stc );
   tran2( (AstObject *) stc, 0.0, 0.0, 5.0, 0.0 );
   printf( "test Negated after %d\n", astTest( stc, "Negated" ) );
   astShow( stc );
   copy = astCopy( stc );
   printf( "equal copy %d\n", astEqual( stc, copy ) );
   stc2 = plain();
   astNegate( stc2 );
   printf( "equal fresh negated %d\n", astEqual( stc, stc2 ) );
   printf( "equal plain %d\n", astEqual( stc, plain() ) );
   failed();

   head( "used_bounded" );
   stc = plain();
   printf( "Bounded %d\n", astGetI( stc, "Bounded" ) );
   printf( "test Negated after %d\n", astTest( stc, "Negated" ) );
   astShow( stc );
   failed();

   /* Review Focus 4: AstroCoords elements. */
   head( "coords_empty" );
   km = astKeyMap( " " );
   stc = astStcResourceProfile( circle2( 0.0, 0.0, 1.0 ), 1, &km, " " );
   printf( "ncoord %d\n", astGetStcNCoord( stc ) );
   astShow( stc );
   show_coord( (AstStc *) stc, 1 );

   head( "coords_value_only" );
   km = astKeyMap( " " );
   astMapPut0A( km, AST__STCVALUE, circle2( 0.5, 0.0, 0.25 ), NULL );
   km2 = astKeyMap( " " );
   {
      const char *names[] = { "X", "Y" };
      astMapPut1C( km2, AST__STCNAME, 2, names, "axis names" );
   }
   astMapPut0A( km2, AST__STCRES, circle2( 0.0, 0.0, 0.01 ), "resolution" );
   kms[ 0 ] = km;
   kms[ 1 ] = km2;
   stc = astStcResourceProfile( circle2( 0.0, 0.0, 1.0 ), 2, kms, " " );
   printf( "ncoord %d\n", astGetStcNCoord( stc ) );
   astShow( stc );
   show_coord( (AstStc *) stc, 1 );
   show_coord( (AstStc *) stc, 2 );
   printf( "-- given KeyMap after\n" );
   astShow( km );
   failed();

   head( "coords_other_frame" );
   km = astKeyMap( " " );
   {
      const char *names[] = { "Frequency" };
      astMapPut1C( km, AST__STCNAME, 1, names, NULL );
   }
   astMapPut0A( km, AST__STCVALUE, spec_interval( "MHz", 1500.0, 1600.0 ), NULL );
   astMapPut0A( km, AST__STCSIZE, spec_interval( "GHz", 0.0, 0.25 ), NULL );
   stc = astStcResourceProfile( spec_interval( "GHz", 1.0, 2.0 ), 1, &km, " " );
   astShow( stc );
   show_coord( (AstStc *) stc, 1 );
   printf( "-- Unit=MHz, Adaptive\n" );
   astSet( stc, "Unit=MHz" );
   astShow( stc );
   show_coord( (AstStc *) stc, 1 );
   printf( "-- region\n" );
   astShow( astGetStcRegion( stc ) );
   failed();

   head( "coords_index" );
   stc = plain();
   astGetStcCoord( stc, 0 );
   failed();
   astGetStcCoord( stc, 1 );
   failed();
   km = astKeyMap( " " );
   stc = astStcResourceProfile( circle2( 0.0, 0.0, 1.0 ), 1, &km, " " );
   astGetStcCoord( stc, 0 );
   failed();
   astGetStcCoord( stc, 2 );
   failed();
   kms[ 0 ] = km;
   kms[ 1 ] = km;
   stc = astStcResourceProfile( circle2( 0.0, 0.0, 1.0 ), 2, kms, " " );
   astGetStcCoord( stc, 3 );
   failed();
   astGetStcCoord( stc, -1 );
   failed();

   head( "coords_bad_keys" );
   reg = (AstRegion *) circle2( 0.0, 0.0, 1.0 );
   printf( "-- unknown key\n" );
   km = astKeyMap( " " );
   astMapPut0A( km, "Colour", circle2( 0.0, 0.0, 1.0 ), NULL );
   make_with( reg, km );
   printf( "-- three names\n" );
   km = astKeyMap( " " );
   {
      const char *names[] = { "X", "Y", "Z" };
      astMapPut1C( km, AST__STCNAME, 3, names, NULL );
   }
   make_with( reg, km );
   printf( "-- names not strings\n" );
   km = astKeyMap( " " );
   {
      int vals[] = { 1, 2 };
      astMapPut1I( km, AST__STCNAME, 2, vals, NULL );
   }
   make_with( reg, km );
   printf( "-- two values\n" );
   km = astKeyMap( " " );
   {
      AstObject *two[ 2 ];
      two[ 0 ] = (AstObject *) circle2( 0.0, 0.0, 1.0 );
      two[ 1 ] = (AstObject *) circle2( 0.0, 0.0, 2.0 );
      astMapPut1A( km, AST__STCVALUE, 2, two, NULL );
   }
   make_with( reg, km );
   printf( "-- value not an Object\n" );
   km = astKeyMap( " " );
   astMapPut0D( km, AST__STCERROR, 1.0, NULL );
   make_with( reg, km );
   printf( "-- value a Frame\n" );
   km = astKeyMap( " " );
   astMapPut0A( km, AST__STCSIZE, astFrame( 2, " " ), NULL );
   make_with( reg, km );
   printf( "-- value in a Frame of three axes\n" );
   km = astKeyMap( " " );
   {
      double lo[] = { 0.0, 0.0, 0.0 }, hi[] = { 1.0, 1.0, 1.0 };
      astMapPut0A( km, AST__STCPIXSZ, astBox( astFrame( 3, " " ), 1, lo, hi, NULL, " " ), NULL );
   }
   make_with( reg, km );

   /* Review Focus 5: attributes set on an Stc. */
   head( "attribs_sky" );
   {
      double c[] = { 1.0, 0.5 }, r = 0.01, rerr = 0.0001;
      const char *names[] = { "RA", "Dec" };
      sky = astSkyFrame( " " );
      km = astKeyMap( " " );
      astMapPut1C( km, AST__STCNAME, 2, names, NULL );
      astMapPut0A( km, AST__STCERROR, astCircle( sky, 1, c, &rerr, NULL, " " ), NULL );
      stc = astStcResourceProfile( astCircle( sky, 1, c, &r, NULL, " " ), 1, &km, " " );
   }
   printf( "RegionClass %s\n", astGetC( stc, "RegionClass" ) );
   printf( "test RegionClass %d\n", astTest( stc, "RegionClass" ) );
   failed();
   astSet( stc, "RegionClass=Box" );
   failed();
   astClear( stc, "RegionClass" );
   failed();
   printf( "-- Adaptive=0, System=Galactic\n" );
   astSet( stc, "Adaptive=0" );
   astSet( stc, "System=Galactic" );
   astShow( stc );
   show_coord( (AstStc *) stc, 1 );
   printf( "-- region\n" );
   astShow( astGetStcRegion( stc ) );
   printf( "-- clear System\n" );
   astClear( stc, "System" );
   astShow( stc );
   show_coord( (AstStc *) stc, 1 );
   failed();

   head( "attribs_region" );
   stc = plain();
   show_attrs( "stc", (AstObject *) stc );
   astSet( stc, "Negated=1" );
   show_attrs( "stc", (AstObject *) stc );
   show_attrs( "region", (AstObject *) astGetStcRegion( stc ) );
   astShow( stc );
   astSet( stc, "Closed=0,MeshSize=40,FillFactor=0.25" );
   show_attrs( "stc", (AstObject *) stc );
   show_attrs( "region", (AstObject *) astGetStcRegion( stc ) );
   astShow( stc );
   printf( "-- clear\n" );
   astClear( stc, "Negated,Closed,MeshSize,FillFactor" );
   show_attrs( "stc", (AstObject *) stc );
   show_attrs( "region", (AstObject *) astGetStcRegion( stc ) );
   astShow( stc );
   printf( "-- set on the region given\n" );
   reg = (AstRegion *) circle2( 0.0, 0.0, 1.0 );
   astSet( reg, "Negated=1,Closed=0,MeshSize=30,FillFactor=0.5" );
   stc = astStcResourceProfile( reg, 0, NULL, " " );
   show_attrs( "stc", (AstObject *) stc );
   astShow( stc );
   failed();

   /* testregions.c checkStcNegated. */
   head( "check_stc_negated" );
   stc = plain();
   tran2( (AstObject *) stc, 0.0, 0.0, 5.0, 0.0 );
   astNegate( stc );
   tran2( (AstObject *) stc, 0.0, 0.0, 5.0, 0.0 );
   printf( "Bounded %d\n", astGetI( stc, "Bounded" ) );
   failed();

   /* Dumps edited by hand and read. */
   head( "begin_stc" );
   dump_lines( (AstObject *) plain() );
   for( i = j = 0; i < nline; i++ ) {
      if( !strcmp( lines[ i ], " Begin StcResourceProfile \t# Resource coverage" ) ) {
         text[ j++ ] = " Begin Stc \t# An IVOA Space-Time-Coords object";
      } else if( !strcmp( lines[ i ], " End StcResourceProfile" ) ) {
         text[ j++ ] = " End Stc";
      } else if( strncmp( lines[ i ], " IsA Stc", 8 ) ) {
         text[ j++ ] = lines[ i ];
      }
   }
   text[ j ] = NULL;
   obj = read_text();
   if( obj ) {
      printf( "ncoord %d\n", astGetStcNCoord( obj ) );
      tran2( obj, 0.0, 0.0, 5.0, 0.0 );
      astShow( obj );
      failed();
   }

   head( "negative_ncoord" );
   dump_lines( (AstObject *) plain() );
   for( i = j = 0; i < nline; i++ ) {
      if( !strncmp( lines[ i ], " IsA Stc", 8 ) ) text[ j++ ] = "    Ncoord = -1";
      text[ j++ ] = lines[ i ];
   }
   text[ j ] = NULL;
   obj = read_text();
   if( obj ) {
      printf( "ncoord %d\n", astGetStcNCoord( obj ) );
      astShow( obj );
      failed();
   }

   astEnd;
   return 0;
}
