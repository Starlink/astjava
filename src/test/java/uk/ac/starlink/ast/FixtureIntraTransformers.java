package uk.ac.starlink.ast;

/**
 * The four transformation functions the C test programs ({@code simplify.c}, {@code roundtrip.c},
 * {@code ast_astequal.c}, {@code transform_oracle_util.c}) register with {@code astIntraReg} for
 * the IntraMap fixtures, with the same names, numbers of coordinates, flags and descriptions.
 */
final class FixtureIntraTransformers {

    private FixtureIntraTransformers() {}

    /**
     * C's {@code TestIntraTran}: each output coordinate is the input coordinate of the same index,
     * or bad beyond the input coordinates, in either direction.
     */
    static final class Identity extends Transformer {
        private final boolean fiFlag;
        private final boolean ifFlag;
        private final String purpose;

        Identity(boolean fiFlag, boolean ifFlag, String purpose) {
            this.fiFlag = fiFlag;
            this.ifFlag = ifFlag;
            this.purpose = purpose;
        }

        @Override
        public boolean canTransformCoords(int nin, int nout) {
            return nin == 1 && nout == 1;
        }

        @Override
        public boolean simpFI() {
            return fiFlag;
        }

        @Override
        public boolean simpIF() {
            return ifFlag;
        }

        @Override
        public double[][] tranP(int npoint, int ncoordIn, double[][] in, boolean forward, int ncoordOut) {
            double[][] out = new double[ncoordOut][npoint];
            for (int ic = 0; ic < ncoordOut; ic++) {
                for (int ip = 0; ip < npoint; ip++) {
                    out[ic][ip] = ic < ncoordIn ? in[ic][ip] : AstObject.AST__BAD;
                }
            }
            return out;
        }

        @Override
        public String getPurpose() {
            return purpose;
        }

        @Override
        public String getAuthor() {
            return "AST test suite";
        }

        @Override
        public String getContact() {
            return "starlink-ast";
        }
    }

    private static final Identity SIMPLIFY_IDENTITY =
            new Identity(true, true, "Identity IntraMap for simplify fixtures");
    private static final Identity SIMPLIFY_IDENTITY2 =
            new Identity(true, true, "Second identity IntraMap for simplify fixtures");
    private static final Identity NOSIMPFI = new Identity(false, true, "IntraMap without SIMPFI for simplify fixtures");
    private static final Identity NOSIMPIF = new Identity(true, false, "IntraMap without SIMPIF for simplify fixtures");

    /** Registers the four functions with {@code ch}, as the C test programs register them. */
    static void register(Channel ch) {
        ch.registerIntra("simplifyidentity", 1, 1, SIMPLIFY_IDENTITY);
        ch.registerIntra("simplifyidentity2", 1, 1, SIMPLIFY_IDENTITY2);
        ch.registerIntra("nosimpfi", 1, 1, NOSIMPFI);
        ch.registerIntra("nosimpif", 1, 1, NOSIMPIF);
    }
}
