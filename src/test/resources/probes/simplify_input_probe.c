/* simplify_input_probe -- what simplifying a Mapping read from a file does
 * to it in the pinned C library: the dump of the Mapping read, of its
 * simplification, and of the Mapping read after it was simplified. Run on
 * ast_tester/fixtures/simplify/brad.map, whose simplification compares
 * CmpMaps that share a WcsMap (cmpmap.c Equal sets the WcsMap's Invert
 * attribute to the value the first CmpMap records).
 *
 * Each case starts with a line "== <row>/<name>", <row> being the name the
 * file's row of ast_tester/fixtures/simplify/cases.txt gives it. Built against the C library at
 * 3d3f255f with its static libraries:
 *   cc -I<build> -o simplify_input_probe simplify_input_probe.c
 *      <build>/libast.a <build>/libast_pal.a <build>/libast_err.a
 *      <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -lyaml -lm
 * and run as ./simplify_input_probe brad <fixtures>/simplify/brad.map >
 * simplify_input_probe.out 2>&1, then trailing white space removed from
 * every line. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ast.h"

static FILE *in = NULL;

static const char *source( void ) {
   static char line[ 1000 ];
   if( !fgets( line, sizeof( line ), in ) ) return NULL;
   line[ strcspn( line, "\n" ) ] = '\0';
   return line;
}

int main( int argc, char **argv ) {
   int status = 0;
   if( argc != 3 || !( in = fopen( argv[ 2 ], "r" ) ) ) return 1;
   setvbuf( stdout, NULL, _IONBF, 0 );
   astWatch( &status );
   astBegin;
   AstChannel *ch = astChannel( source, NULL, " " );
   AstMapping *m = astRead( ch );
   printf( "== %s/input_before\n", argv[ 1 ] );
   astShow( m );
   AstMapping *s = astSimplify( m );
   printf( "== %s/simplified\n", argv[ 1 ] );
   astShow( s );
   printf( "== %s/input_after\n", argv[ 1 ] );
   astShow( m );
   astEnd;
   fclose( in );
   return 0;
}
