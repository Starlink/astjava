/* fitschan_wcsaxes_round_probe -- reading a FITS-WCS header whose WCSAXES
 * is not a whole number, by the pinned C library.
 *
 * fitschan.c WcsFcRead and the other readers of WCSAXES take it as a
 * floating value and round it with C's round(). The value
 * 0.49999999999999994, the largest double below a half, rounds to 0, where
 * floor( x + 0.5 ) gives 1, since x + 0.5 rounds up to 1.
 *
 * Each case reads, with astRead, a one-axis header (NAXIS 1, CTYPE1 X,
 * CRPIX1 1, CRVAL1 0, CDELT1 1) whose WCSAXES card has the value named,
 * and gives the class, Nin and Nout of the Object read ("read"), or the
 * status of the failure ("status").
 *
 * Each case starts with a line "== <name>". C's error messages go to
 * standard error, with standard output unbuffered so that they fall within
 * their case.
 *
 * Built against the C library at 3d3f255f with its static libraries:
 *   cc -std=c11 -ffp-contract=off -I<build> -o fitschan_wcsaxes_round_probe
 *      fitschan_wcsaxes_round_probe.c <build>/libast.a <build>/libast_pal.a
 *      <build>/libast_err.a <build>/libast_grf3d.a <build>/libast_grf_2.0.a
 *      <build>/libast_grf_3.2.a <build>/libast_grf_5.6.a
 *      <build>/libast_cminpack.a -L<libyaml>/lib -lyaml -lm
 * and run as ./fitschan_wcsaxes_round_probe > fitschan_wcsaxes_round_probe.out
 * 2>&1, with trailing white space then removed from every line. */
#include <stdio.h>
#include "ast.h"

static void run( const char *value ) {
   char card[ 81 ];
   AstFitsChan *fc;
   AstObject *obj;

   astBegin;
   printf( "== %s\n", value );
   fc = astFitsChan( NULL, NULL, " " );
   astPutFits( fc, "NAXIS   =                    1", 0 );
   astPutFits( fc, "NAXIS1  =                   10", 0 );
   sprintf( card, "WCSAXES = %20s", value );
   astPutFits( fc, card, 0 );
   astPutFits( fc, "CTYPE1  = 'X       '", 0 );
   astPutFits( fc, "CRPIX1  =                  1.0", 0 );
   astPutFits( fc, "CRVAL1  =                  0.0", 0 );
   astPutFits( fc, "CDELT1  =                  1.0", 0 );
   astClear( fc, "Card" );
   obj = astRead( fc );
   if( !astOK ) {
      printf( "status %d\n", astStatus );
      astClearStatus;
   } else if( !obj ) {
      printf( "read none\n" );
   } else {
      printf( "read %s %d %d\n", astGetC( obj, "Class" ), astGetI( obj, "Nin" ),
              astGetI( obj, "Nout" ) );
   }
   astEnd;
}

int main( void ) {
   setvbuf( stdout, NULL, _IONBF, 0 );
   run( "0" );
   run( "0.49999999999999994" );
   run( "0.5" );
   run( "1" );
   return 0;
}
