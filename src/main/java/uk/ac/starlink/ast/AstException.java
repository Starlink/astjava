package uk.ac.starlink.ast;

/**
 * Thrown to indicate that there has been an AST error.
 *
 * <p>The exception carries a status value, which is one of the {@code static final int} fields of
 * this class: the {@code AST__} constants name the conditions the AST library reports, with the
 * values the C library gives them. {@link #getStatus} returns the value and {@link #getStatusName}
 * its name, so a caller can find out exactly what went wrong. An error that does not come from the
 * AST library itself has the status {@link #SAI__ERROR}.
 */
public class AstException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Status constant for no error; it is never the status of an AstException. */
    public static final int SAI__OK = 0;

    /**
     * Status constant for an unknown error, used where no value defined by the AST library is
     * appropriate.
     */
    public static final int SAI__ERROR = 148013867;

    /** The status value. */
    private final int status;

    /**
     * Constructs an AstException with the status {@link #SAI__ERROR}.
     *
     * @param message an explanatory message
     */
    public AstException(String message) {
        this(message, SAI__ERROR);
    }

    /**
     * Constructs an AstException with a given status value. The exception's message is
     * {@code msgText} followed by the status name in parentheses.
     *
     * @param msgText an explanatory message
     * @param status the status value, normally one of the constants of this class
     */
    public AstException(String msgText, int status) {
        super(msgText + " (" + statusName(status) + ")");
        this.status = status;
    }

    /**
     * Returns the status value of this exception, which ought to be the value of one of this
     * class's constants.
     *
     * @return the status value
     */
    public int getStatus() {
        return status;
    }

    /**
     * Returns the name of this exception's status value, such as {@code "AST__BADAT"}, or null if
     * the value is not that of one of this class's constants.
     *
     * @return the status name, or null
     */
    public String getStatusName() {
        return statusName(status);
    }

    /** The name of the constant whose value is {@code status}, or null. */
    static String statusName(int status) {
        if (status == SAI__OK) {
            return "SAI__OK";
        }
        if (status == SAI__ERROR) {
            return "SAI__ERROR";
        }
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i] == status) {
                return NAMES[i];
            }
        }
        return null;
    }

    /** The AST library's description of the condition {@code status} names, or null. */
    static String describe(int status) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i] == status) {
                return DESCRIPTIONS[i];
            }
        }
        return null;
    }

    // BEGIN GENERATED ERROR CODES (tools/GenErrorCodes.java; do not edit)

    /** Status constant for the error "attribute getting error". */
    public static final int AST__ATGER = 233933154;

    /** Status constant for the error "attribute setting error". */
    public static final int AST__ATSER = 233933162;

    /** Status constant for the error "attribute value invalid". */
    public static final int AST__ATTIN = 233933170;

    /** Status constant for the error "axis index invalid". */
    public static final int AST__AXIIN = 233933178;

    /** Status constant for the error "bad attribute name". */
    public static final int AST__BADAT = 233933186;

    /** Status constant for the error "zero-sized box given". */
    public static final int AST__BADBX = 233933194;

    /** Status constant for the error "bad input data". */
    public static final int AST__BADIN = 233933202;

    /** Status constant for the error "bad number of input coordinates". */
    public static final int AST__BADNI = 233933210;

    /** Status constant for the error "bad number of output coordinates". */
    public static final int AST__BADNO = 233933218;

    /** Status constant for the error "PolyMap contains illegal power value". */
    public static final int AST__BADPW = 233933226;

    /** Status constant for the error "ShiftMap contains no shift information". */
    public static final int AST__BADSM = 233933234;

    /** Status constant for the error "WinMap contains no bounds information". */
    public static final int AST__BADWM = 233933242;

    /** Status constant for the error "bad break index". */
    public static final int AST__BDBRK = 233933250;

    /** Status constant for the error "bad field specifier". */
    public static final int AST__BDFMT = 233933258;

    /** Status constant for the error "invalid FITS keyword value found". */
    public static final int AST__BDFTS = 233933266;

    /** Status constant for the error "inappropriate Object supplied". */
    public static final int AST__BDOBJ = 233933274;

    /** Status constant for the error "wrong number of clipping axes". */
    public static final int AST__CLPAX = 233933282;

    /** Status constant for the error "range of coordinates invalid". */
    public static final int AST__CORNG = 233933290;

    /** Status constant for the error "too many breaks in a curve". */
    public static final int AST__CVBRK = 233933298;

    /** Status constant for the error "array dimensions invalid". */
    public static final int AST__DIMIN = 233933306;

    /** Status constant for the error "date/time error". */
    public static final int AST__DTERR = 233933314;

    /** Status constant for the error "invalid use of astEnd". */
    public static final int AST__ENDIN = 233933322;

    /** Status constant for the error "end of input Channel encountered". */
    public static final int AST__EOCHN = 233933330;

    /** Status constant for the error "attempt to export Object pointer from level zero". */
    public static final int AST__EXPIN = 233933338;

    /** Status constant for the error "corrupted FitsChan supplied". */
    public static final int AST__FCRPT = 233933346;

    /** Status constant for the error "error while formatting coordinate value". */
    public static final int AST__FMTER = 233933354;

    /** Status constant for the error "Frame index invalid". */
    public static final int AST__FRMIN = 233933362;

    /** Status constant for the error "FrameSet invalid". */
    public static final int AST__FRSIN = 233933370;

    /** Status constant for the error "cannot convert FITS data value type". */
    public static final int AST__FTCNV = 233933378;

    /** Status constant for the error "low level graphics error". */
    public static final int AST__GRFER = 233933386;

    /** Status constant for the error "invalid Handle". */
    public static final int AST__INHAN = 233933394;

    /** Status constant for the error "incompatible numbers of coordinates". */
    public static final int AST__INNCO = 233933402;

    /** Status constant for the error "internal programming error". */
    public static final int AST__INTER = 233933410;

    /** Status constant for the error "incompatible transformation directions". */
    public static final int AST__INTRD = 233933418;

    /** Status constant for the error "circular dependency between KeyMaps". */
    public static final int AST__KYCIR = 233933426;

    /** Status constant for the error "class loader error". */
    public static final int AST__LDERR = 233933434;

    /** Status constant for the error "invalid lookup table increment". */
    public static final int AST__LUTII = 233933442;

    /** Status constant for the error "invalid number of lookup table elements". */
    public static final int AST__LUTIN = 233933450;

    /** Status constant for the error "requested memory size invalid". */
    public static final int AST__MEMIN = 233933458;

    /** Status constant for the error "not a 2d or 3d MatrixMap". */
    public static final int AST__MTR23 = 233933466;

    /** Status constant for the error "null rotation axis supplied". */
    public static final int AST__MTRAX = 233933474;

    /** Status constant for the error "bad matrix shapes for multiplication". */
    public static final int AST__MTRML = 233933482;

    /** Status constant for the error "null matrix supplied". */
    public static final int AST__MTRMT = 233933490;

    /** Status constant for the error "number of axes invalid". */
    public static final int AST__NAXIN = 233933498;

    /** Status constant for the error "number of characters invalid". */
    public static final int AST__NCHIN = 233933506;

    /** Status constant for the error "number of coordinates invalid". */
    public static final int AST__NCOIN = 233933514;

    /** Status constant for the error "number of coordinates per point invalid". */
    public static final int AST__NCPIN = 233933522;

    /** Status constant for the error "number of array elements invalid". */
    public static final int AST__NELIN = 233933530;

    /** Status constant for the error "number of output coordinates too small". */
    public static final int AST__NOCTS = 233933538;

    /** Status constant for the error "transformation not defined". */
    public static final int AST__NODEF = 233933546;

    /** Status constant for the error "required FITS keywords missing". */
    public static final int AST__NOFTS = 233933554;

    /** Status constant for the error "unable to allocate memory". */
    public static final int AST__NOMEM = 233933562;

    /** Status constant for the error "number of output points too small". */
    public static final int AST__NOPTS = 233933570;

    /** Status constant for the error "attribute is read-only". */
    public static final int AST__NOWRT = 233933578;

    /** Status constant for the error "number of points invalid". */
    public static final int AST__NPTIN = 233933586;

    /** Status constant for the error "Object invalid". */
    public static final int AST__OBJIN = 233933594;

    /** Status constant for the error "invalid Plot option". */
    public static final int AST__OPT = 233933602;

    /** Status constant for the error "points data structure invalid". */
    public static final int AST__PDSIN = 233933610;

    /** Status constant for the error "no numerical labels can be produced". */
    public static final int AST__PLFMT = 233933618;

    /** Status constant for the error "permutation invalid". */
    public static final int AST__PRMIN = 233933626;

    /** Status constant for the error "pointer invalid". */
    public static final int AST__PTRIN = 233933634;

    /** Status constant for the error "range of points invalid". */
    public static final int AST__PTRNG = 233933642;

    /** Status constant for the error "read error". */
    public static final int AST__RDERR = 233933650;

    /** Status constant for the error "invalid or corrupted Region structure supplied". */
    public static final int AST__REGIN = 233933658;

    /** Status constant for the error "invalid attempt to remove last Frame". */
    public static final int AST__REMIN = 233933666;

    /** Status constant for the error "sky coordinate system invalid". */
    public static final int AST__SCSIN = 233933674;

    /** Status constant for the error "axis selection invalid". */
    public static final int AST__SELIN = 233933682;

    /** Status constant for the error "bad SLALIB transformation type". */
    public static final int AST__SLAIN = 233933690;

    /** Status constant for the error "coordinate transformation not defined". */
    public static final int AST__TRNND = 233933698;

    /** Status constant for the error "unmatched quotes". */
    public static final int AST__UNMQT = 233933706;

    /** Status constant for the error "valid area too small". */
    public static final int AST__VSMAL = 233933714;

    /** Status constant for the error "non-existent longitude or latitude axis". */
    public static final int AST__WCSAX = 233933722;

    /** Status constant for the error "too few mapping coordinates". */
    public static final int AST__WCSNC = 233933730;

    /** Status constant for the error "invalid projection parameters". */
    public static final int AST__WCSPA = 233933738;

    /** Status constant for the error "unknown projection type". */
    public static final int AST__WCSTY = 233933746;

    /** Status constant for the error "too many Objects in use at once". */
    public static final int AST__XSOBJ = 233933754;

    /** Status constant for the error "zoom factor invalid". */
    public static final int AST__ZOOMI = 233933762;

    /** Status constant for the error "bad coordinate index". */
    public static final int AST__BADCI = 233933770;

    /** Status constant for the error "FrameSet integrity lost". */
    public static final int AST__ILOST = 233933778;

    /** Status constant for the error "error in IntraMap transformation function". */
    public static final int AST__ITFER = 233933786;

    /** Status constant for the error "IntraMap transformation function name invalid". */
    public static final int AST__ITFNI = 233933794;

    /** Status constant for the error "Mapping bounding box not found". */
    public static final int AST__MBBNF = 233933802;

    /** Status constant for the error "multiple registration of IntraMap transformation function". */
    public static final int AST__MRITF = 233933810;

    /** Status constant for the error "Object class unknown". */
    public static final int AST__OCLUK = 233933818;

    /** Status constant for the error "error while unformatting a coordinate value". */
    public static final int AST__UNFER = 233933826;

    /** Status constant for the error "unregistered IntraMap transformation function". */
    public static final int AST__URITF = 233933834;

    /** Status constant for the error "grid bounds invalid". */
    public static final int AST__GBDIN = 233933842;

    /** Status constant for the error "number of grid dimensions invalid". */
    public static final int AST__NGDIN = 233933850;

    /** Status constant for the error "positional accuracy tolerance invalid". */
    public static final int AST__PATIN = 233933858;

    /** Status constant for the error "sub-pixel interpolation scheme invalid". */
    public static final int AST__SISIN = 233933866;

    /** Status constant for the error "scale size in pixels invalid". */
    public static final int AST__SSPIN = 233933874;

    /** Status constant for the error "error in user-supplied sub-pixel interpolation function". */
    public static final int AST__UINER = 233933882;

    /** Status constant for the error "error in user-supplied 1-d sub-pixel interpolation kernel". */
    public static final int AST__UK1ER = 233933890;

    /** Status constant for the error "invalid comma in expression". */
    public static final int AST__COMIN = 233933898;

    /** Status constant for the error "invalid constant in expression". */
    public static final int AST__CONIN = 233933906;

    /** Status constant for the error "duplicate variable name". */
    public static final int AST__DUVAR = 233933914;

    /** Status constant for the error "invalid number of transformation functions". */
    public static final int AST__INNTF = 233933922;

    /** Status constant for the error "missing or invalid operand in expression". */
    public static final int AST__MIOPA = 233933930;

    /** Status constant for the error "missing or invalid operator in expression". */
    public static final int AST__MIOPR = 233933938;

    /** Status constant for the error "missing variable name". */
    public static final int AST__MISVN = 233933946;

    /** Status constant for the error "missing left parenthesis in expression". */
    public static final int AST__MLPAR = 233933954;

    /** Status constant for the error "missing right parenthesis in expression". */
    public static final int AST__MRPAR = 233933962;

    /** Status constant for the error "missing right hand side in function". */
    public static final int AST__NORHS = 233933970;

    /** Status constant for the error "undefined variable or function in expression". */
    public static final int AST__UDVOF = 233933978;

    /** Status constant for the error "variable name invalid". */
    public static final int AST__VARIN = 233933986;

    /** Status constant for the error "wrong number of function arguments in expression". */
    public static final int AST__WRNFA = 233933994;

    /** Status constant for the error "invalid units specification". */
    public static final int AST__BADUN = 233934002;

    /** Status constant for the error "no rest frequency is defined". */
    public static final int AST__NORSF = 233934010;

    /** Status constant for the error "no standard of rest is defined". */
    public static final int AST__NOSOR = 233934018;

    /** Status constant for the error "invalid SpecMap". */
    public static final int AST__SPCIN = 233934026;

    /** Status constant for the error "invalid XML name or prefix". */
    public static final int AST__XMLNM = 233934034;

    /** Status constant for the error "invalid XML comment text". */
    public static final int AST__XMLCM = 233934042;

    /** Status constant for the error "invalid XML processing instruction target text". */
    public static final int AST__XMLPT = 233934050;

    /** Status constant for the error "invalid XML content item index". */
    public static final int AST__XMLIT = 233934058;

    /** Status constant for the error "supplied XML document is not well formed". */
    public static final int AST__XMLWF = 233934066;

    /** Status constant for the error "Range of log axis scale includes zero". */
    public static final int AST__ZERAX = 233934074;

    /** Status constant for the error "Invalid parameters for offset sky coordinate system". */
    public static final int AST__BADOC = 233934082;

    /** Status constant for the error "error getting a named value from a KeyMap". */
    public static final int AST__MPGER = 233934090;

    /** Status constant for the error "invalid integer index supplied for a KeyMap entry". */
    public static final int AST__MPIND = 233934098;

    /** Status constant for the error "region cannot be re-centred". */
    public static final int AST__REGCN = 233934106;

    /** Status constant for the error "attribute has no usable value". */
    public static final int AST__NOVAL = 233934114;

    /** Status constant for the error "incompatible time scales". */
    public static final int AST__INCTS = 233934122;

    /** Status constant for the error "invalid TimeMap". */
    public static final int AST__TIMIN = 233934130;

    /** Status constant for the error "cannot use supplied AstroCoords info". */
    public static final int AST__STCKEY = 233934138;

    /** Status constant for the error "invalid AstroCoords index". */
    public static final int AST__STCIND = 233934146;

    /** Status constant for the error "cannot conserve flux whilst resampling an array of data". */
    public static final int AST__CNFLX = 233934154;

    /** Status constant for the error "Unknown AST tuning parameter name supplied". */
    public static final int AST__TUNAM = 233934162;

    /** Status constant for the error "Bad value supplied for a public function parameter". */
    public static final int AST__BDPAR = 233934170;

    /** Status constant for the error "Supplied FrameSet does not contain any independent axes". */
    public static final int AST__3DFSET = 233934178;

    /** Status constant for the error "Attempt to delete original Plot3D base Frame". */
    public static final int AST__PXFRRM = 233934186;

    /** Status constant for the error "Illegal syntax for string substitution template". */
    public static final int AST__BADSUB = 233934194;

    /** Status constant for the error "Incompatible flags for re-sampling or re-binning". */
    public static final int AST__BADFLG = 233934202;

    /** Status constant for the error "Error locking or unlocking an AST Object". */
    public static final int AST__LCKERR = 233934210;

    /** Status constant for the error "FITS keyword had undefined value". */
    public static final int AST__FUNDEF = 233934218;

    /** Status constant for the error "invalid integer index supplied for a KeyMap vector element". */
    public static final int AST__MPVIN = 233934226;

    /** Status constant for the error "operation specifier invalid". */
    public static final int AST__OPRIN = 233934234;

    /** Status constant for the error "no inside point found". */
    public static final int AST__NONIN = 233934242;

    /** Status constant for the error "requested key not found in KeyMap". */
    public static final int AST__MPKER = 233934250;

    /** Status constant for the error "error putting a named value into a KeyMap". */
    public static final int AST__MPPER = 233934258;

    /** Status constant for the error "Attempt made to add an entry to a locked KeyMap". */
    public static final int AST__BADKEY = 233934266;

    /** Status constant for the error "Bad data type". */
    public static final int AST__BADTYP = 233934274;

    /** Status constant for the error "Column already exists with different properties". */
    public static final int AST__OLDCOL = 233934282;

    /** Status constant for the error "Bad null value for a FITS table column". */
    public static final int AST__BADNULL = 233934290;

    /** Status constant for the error "Key string is too long". */
    public static final int AST__BIGKEY = 233934298;

    /** Status constant for the error "No such column exists in the table". */
    public static final int AST__BADCOL = 233934306;

    /** Status constant for the error "Table is too large". */
    public static final int AST__BIGTAB = 233934314;

    /** Status constant for the error "Invalid array size". */
    public static final int AST__BADSIZ = 233934322;

    /** Status constant for the error "Error reading WCS from FITS binary table". */
    public static final int AST__BADTAB = 233934330;

    /** Status constant for the error "Cannot access FITS binary table". */
    public static final int AST__NOTAB = 233934338;

    /** Status constant for the error "Error in levmar Levenberg-Marquardt code". */
    public static final int AST__LEVMAR = 233934346;

    /** Status constant for the error "Fit failed". */
    public static final int AST__NOFIT = 233934354;

    /** Status constant for the error "A transformation generated one or more NaN values". */
    public static final int AST__ISNAN = 233934362;

    /** Status constant for the error "write error". */
    public static final int AST__WRERR = 233934370;

    /** Status constant for the error "Bad variant Mapping name". */
    public static final int AST__BDVNM = 233934378;

    /** Status constant for the error "Attempt to add a variant Mapping to a mirror Frame". */
    public static final int AST__MIRRO = 233934386;

    /** Status constant for the error "Error in cminpack Levenberg-Marquardt code". */
    public static final int AST__MNPCK = 233934394;

    /** Status constant for the error "Supplied array has too many pixels". */
    public static final int AST__EXSPIX = 233934402;

    /** Status constant for the error "No mapping found between coordinate systems". */
    public static final int AST__NOCNV = 233934410;

    /** Status constant for the error "Attempt to change an immutable attribute". */
    public static final int AST__IMMUT = 233934418;

    /** Status constant for the error "No bounding box available". */
    public static final int AST__NOBOX = 233934426;

    /** Status constant for the error "Supplied Frame has no sky coordinate axes". */
    public static final int AST__NOSKY = 233934434;

    /** Status constant for the error "Sky axes cannot be separated from other axes". */
    public static final int AST__BDSKY = 233934442;

    /** Status constant for the error "Wrong class of object supplied". */
    public static final int AST__BDCLS = 233934450;

    /** Status constant for the error "Class does not implement the invoked method". */
    public static final int AST__NOIMP = 233934458;

    /** Status constant for the error "Normalised MOC is too big". */
    public static final int AST__BGMOC = 233934466;

    /** Status constant for the error "Invalid argument value supplied to an AST method". */
    public static final int AST__INVAR = 233934474;

    /** Status constant for the error "Invalid string-encoded MOC". */
    public static final int AST__INMOC = 233934482;

    /** Status constant for the error "Supplied buffer too small". */
    public static final int AST__SMBUF = 233934490;

    /** Status constant for the error "Word is too long". */
    public static final int AST__BGWRD = 233934498;

    /** Status constant for the error "Array is too big". */
    public static final int AST__TOOBG = 233934506;

    /** Status constant for the error "Requested feature is not supported". */
    public static final int AST__NOTSP = 233934514;

    /** Status constant for the error "Text truncated". */
    public static final int AST__TRUNC = 233934522;

    /** Status constant for the error "Error in libyaml function". */
    public static final int AST__LYAML = 233934530;

    /** Status constant for the error "Error reading YAML text". */
    public static final int AST__YAML = 233934538;

    /** Status constant for the error "AST was configured without YAML support". */
    public static final int AST__NOYAML = 233934546;

    /** Status constant for the error "No TMapping found". */
    public static final int AST__NOTMP = 233934554;

    /** Status constant for the error "Invalid YAML object supplied". */
    public static final int AST__BYAML = 233934562;

    /** Status constant for the error "Invalid ASDF object supplied". */
    public static final int AST__BASDF = 233934570;

    /** Status constant for the error "Unsupported ASDF object supplied". */
    public static final int AST__UASDF = 233934578;

    private static final int[] CODES = {
        AST__ATGER,
        AST__ATSER,
        AST__ATTIN,
        AST__AXIIN,
        AST__BADAT,
        AST__BADBX,
        AST__BADIN,
        AST__BADNI,
        AST__BADNO,
        AST__BADPW,
        AST__BADSM,
        AST__BADWM,
        AST__BDBRK,
        AST__BDFMT,
        AST__BDFTS,
        AST__BDOBJ,
        AST__CLPAX,
        AST__CORNG,
        AST__CVBRK,
        AST__DIMIN,
        AST__DTERR,
        AST__ENDIN,
        AST__EOCHN,
        AST__EXPIN,
        AST__FCRPT,
        AST__FMTER,
        AST__FRMIN,
        AST__FRSIN,
        AST__FTCNV,
        AST__GRFER,
        AST__INHAN,
        AST__INNCO,
        AST__INTER,
        AST__INTRD,
        AST__KYCIR,
        AST__LDERR,
        AST__LUTII,
        AST__LUTIN,
        AST__MEMIN,
        AST__MTR23,
        AST__MTRAX,
        AST__MTRML,
        AST__MTRMT,
        AST__NAXIN,
        AST__NCHIN,
        AST__NCOIN,
        AST__NCPIN,
        AST__NELIN,
        AST__NOCTS,
        AST__NODEF,
        AST__NOFTS,
        AST__NOMEM,
        AST__NOPTS,
        AST__NOWRT,
        AST__NPTIN,
        AST__OBJIN,
        AST__OPT,
        AST__PDSIN,
        AST__PLFMT,
        AST__PRMIN,
        AST__PTRIN,
        AST__PTRNG,
        AST__RDERR,
        AST__REGIN,
        AST__REMIN,
        AST__SCSIN,
        AST__SELIN,
        AST__SLAIN,
        AST__TRNND,
        AST__UNMQT,
        AST__VSMAL,
        AST__WCSAX,
        AST__WCSNC,
        AST__WCSPA,
        AST__WCSTY,
        AST__XSOBJ,
        AST__ZOOMI,
        AST__BADCI,
        AST__ILOST,
        AST__ITFER,
        AST__ITFNI,
        AST__MBBNF,
        AST__MRITF,
        AST__OCLUK,
        AST__UNFER,
        AST__URITF,
        AST__GBDIN,
        AST__NGDIN,
        AST__PATIN,
        AST__SISIN,
        AST__SSPIN,
        AST__UINER,
        AST__UK1ER,
        AST__COMIN,
        AST__CONIN,
        AST__DUVAR,
        AST__INNTF,
        AST__MIOPA,
        AST__MIOPR,
        AST__MISVN,
        AST__MLPAR,
        AST__MRPAR,
        AST__NORHS,
        AST__UDVOF,
        AST__VARIN,
        AST__WRNFA,
        AST__BADUN,
        AST__NORSF,
        AST__NOSOR,
        AST__SPCIN,
        AST__XMLNM,
        AST__XMLCM,
        AST__XMLPT,
        AST__XMLIT,
        AST__XMLWF,
        AST__ZERAX,
        AST__BADOC,
        AST__MPGER,
        AST__MPIND,
        AST__REGCN,
        AST__NOVAL,
        AST__INCTS,
        AST__TIMIN,
        AST__STCKEY,
        AST__STCIND,
        AST__CNFLX,
        AST__TUNAM,
        AST__BDPAR,
        AST__3DFSET,
        AST__PXFRRM,
        AST__BADSUB,
        AST__BADFLG,
        AST__LCKERR,
        AST__FUNDEF,
        AST__MPVIN,
        AST__OPRIN,
        AST__NONIN,
        AST__MPKER,
        AST__MPPER,
        AST__BADKEY,
        AST__BADTYP,
        AST__OLDCOL,
        AST__BADNULL,
        AST__BIGKEY,
        AST__BADCOL,
        AST__BIGTAB,
        AST__BADSIZ,
        AST__BADTAB,
        AST__NOTAB,
        AST__LEVMAR,
        AST__NOFIT,
        AST__ISNAN,
        AST__WRERR,
        AST__BDVNM,
        AST__MIRRO,
        AST__MNPCK,
        AST__EXSPIX,
        AST__NOCNV,
        AST__IMMUT,
        AST__NOBOX,
        AST__NOSKY,
        AST__BDSKY,
        AST__BDCLS,
        AST__NOIMP,
        AST__BGMOC,
        AST__INVAR,
        AST__INMOC,
        AST__SMBUF,
        AST__BGWRD,
        AST__TOOBG,
        AST__NOTSP,
        AST__TRUNC,
        AST__LYAML,
        AST__YAML,
        AST__NOYAML,
        AST__NOTMP,
        AST__BYAML,
        AST__BASDF,
        AST__UASDF,
    };

    private static final String[] NAMES = {
        "AST__ATGER",
        "AST__ATSER",
        "AST__ATTIN",
        "AST__AXIIN",
        "AST__BADAT",
        "AST__BADBX",
        "AST__BADIN",
        "AST__BADNI",
        "AST__BADNO",
        "AST__BADPW",
        "AST__BADSM",
        "AST__BADWM",
        "AST__BDBRK",
        "AST__BDFMT",
        "AST__BDFTS",
        "AST__BDOBJ",
        "AST__CLPAX",
        "AST__CORNG",
        "AST__CVBRK",
        "AST__DIMIN",
        "AST__DTERR",
        "AST__ENDIN",
        "AST__EOCHN",
        "AST__EXPIN",
        "AST__FCRPT",
        "AST__FMTER",
        "AST__FRMIN",
        "AST__FRSIN",
        "AST__FTCNV",
        "AST__GRFER",
        "AST__INHAN",
        "AST__INNCO",
        "AST__INTER",
        "AST__INTRD",
        "AST__KYCIR",
        "AST__LDERR",
        "AST__LUTII",
        "AST__LUTIN",
        "AST__MEMIN",
        "AST__MTR23",
        "AST__MTRAX",
        "AST__MTRML",
        "AST__MTRMT",
        "AST__NAXIN",
        "AST__NCHIN",
        "AST__NCOIN",
        "AST__NCPIN",
        "AST__NELIN",
        "AST__NOCTS",
        "AST__NODEF",
        "AST__NOFTS",
        "AST__NOMEM",
        "AST__NOPTS",
        "AST__NOWRT",
        "AST__NPTIN",
        "AST__OBJIN",
        "AST__OPT",
        "AST__PDSIN",
        "AST__PLFMT",
        "AST__PRMIN",
        "AST__PTRIN",
        "AST__PTRNG",
        "AST__RDERR",
        "AST__REGIN",
        "AST__REMIN",
        "AST__SCSIN",
        "AST__SELIN",
        "AST__SLAIN",
        "AST__TRNND",
        "AST__UNMQT",
        "AST__VSMAL",
        "AST__WCSAX",
        "AST__WCSNC",
        "AST__WCSPA",
        "AST__WCSTY",
        "AST__XSOBJ",
        "AST__ZOOMI",
        "AST__BADCI",
        "AST__ILOST",
        "AST__ITFER",
        "AST__ITFNI",
        "AST__MBBNF",
        "AST__MRITF",
        "AST__OCLUK",
        "AST__UNFER",
        "AST__URITF",
        "AST__GBDIN",
        "AST__NGDIN",
        "AST__PATIN",
        "AST__SISIN",
        "AST__SSPIN",
        "AST__UINER",
        "AST__UK1ER",
        "AST__COMIN",
        "AST__CONIN",
        "AST__DUVAR",
        "AST__INNTF",
        "AST__MIOPA",
        "AST__MIOPR",
        "AST__MISVN",
        "AST__MLPAR",
        "AST__MRPAR",
        "AST__NORHS",
        "AST__UDVOF",
        "AST__VARIN",
        "AST__WRNFA",
        "AST__BADUN",
        "AST__NORSF",
        "AST__NOSOR",
        "AST__SPCIN",
        "AST__XMLNM",
        "AST__XMLCM",
        "AST__XMLPT",
        "AST__XMLIT",
        "AST__XMLWF",
        "AST__ZERAX",
        "AST__BADOC",
        "AST__MPGER",
        "AST__MPIND",
        "AST__REGCN",
        "AST__NOVAL",
        "AST__INCTS",
        "AST__TIMIN",
        "AST__STCKEY",
        "AST__STCIND",
        "AST__CNFLX",
        "AST__TUNAM",
        "AST__BDPAR",
        "AST__3DFSET",
        "AST__PXFRRM",
        "AST__BADSUB",
        "AST__BADFLG",
        "AST__LCKERR",
        "AST__FUNDEF",
        "AST__MPVIN",
        "AST__OPRIN",
        "AST__NONIN",
        "AST__MPKER",
        "AST__MPPER",
        "AST__BADKEY",
        "AST__BADTYP",
        "AST__OLDCOL",
        "AST__BADNULL",
        "AST__BIGKEY",
        "AST__BADCOL",
        "AST__BIGTAB",
        "AST__BADSIZ",
        "AST__BADTAB",
        "AST__NOTAB",
        "AST__LEVMAR",
        "AST__NOFIT",
        "AST__ISNAN",
        "AST__WRERR",
        "AST__BDVNM",
        "AST__MIRRO",
        "AST__MNPCK",
        "AST__EXSPIX",
        "AST__NOCNV",
        "AST__IMMUT",
        "AST__NOBOX",
        "AST__NOSKY",
        "AST__BDSKY",
        "AST__BDCLS",
        "AST__NOIMP",
        "AST__BGMOC",
        "AST__INVAR",
        "AST__INMOC",
        "AST__SMBUF",
        "AST__BGWRD",
        "AST__TOOBG",
        "AST__NOTSP",
        "AST__TRUNC",
        "AST__LYAML",
        "AST__YAML",
        "AST__NOYAML",
        "AST__NOTMP",
        "AST__BYAML",
        "AST__BASDF",
        "AST__UASDF",
    };

    private static final String[] DESCRIPTIONS = {
        "attribute getting error",
        "attribute setting error",
        "attribute value invalid",
        "axis index invalid",
        "bad attribute name",
        "zero-sized box given",
        "bad input data",
        "bad number of input coordinates",
        "bad number of output coordinates",
        "PolyMap contains illegal power value",
        "ShiftMap contains no shift information",
        "WinMap contains no bounds information",
        "bad break index",
        "bad field specifier",
        "invalid FITS keyword value found",
        "inappropriate Object supplied",
        "wrong number of clipping axes",
        "range of coordinates invalid",
        "too many breaks in a curve",
        "array dimensions invalid",
        "date/time error",
        "invalid use of astEnd",
        "end of input Channel encountered",
        "attempt to export Object pointer from level zero",
        "corrupted FitsChan supplied",
        "error while formatting coordinate value",
        "Frame index invalid",
        "FrameSet invalid",
        "cannot convert FITS data value type",
        "low level graphics error",
        "invalid Handle",
        "incompatible numbers of coordinates",
        "internal programming error",
        "incompatible transformation directions",
        "circular dependency between KeyMaps",
        "class loader error",
        "invalid lookup table increment",
        "invalid number of lookup table elements",
        "requested memory size invalid",
        "not a 2d or 3d MatrixMap",
        "null rotation axis supplied",
        "bad matrix shapes for multiplication",
        "null matrix supplied",
        "number of axes invalid",
        "number of characters invalid",
        "number of coordinates invalid",
        "number of coordinates per point invalid",
        "number of array elements invalid",
        "number of output coordinates too small",
        "transformation not defined",
        "required FITS keywords missing",
        "unable to allocate memory",
        "number of output points too small",
        "attribute is read-only",
        "number of points invalid",
        "Object invalid",
        "invalid Plot option",
        "points data structure invalid",
        "no numerical labels can be produced",
        "permutation invalid",
        "pointer invalid",
        "range of points invalid",
        "read error",
        "invalid or corrupted Region structure supplied",
        "invalid attempt to remove last Frame",
        "sky coordinate system invalid",
        "axis selection invalid",
        "bad SLALIB transformation type",
        "coordinate transformation not defined",
        "unmatched quotes",
        "valid area too small",
        "non-existent longitude or latitude axis",
        "too few mapping coordinates",
        "invalid projection parameters",
        "unknown projection type",
        "too many Objects in use at once",
        "zoom factor invalid",
        "bad coordinate index",
        "FrameSet integrity lost",
        "error in IntraMap transformation function",
        "IntraMap transformation function name invalid",
        "Mapping bounding box not found",
        "multiple registration of IntraMap transformation function",
        "Object class unknown",
        "error while unformatting a coordinate value",
        "unregistered IntraMap transformation function",
        "grid bounds invalid",
        "number of grid dimensions invalid",
        "positional accuracy tolerance invalid",
        "sub-pixel interpolation scheme invalid",
        "scale size in pixels invalid",
        "error in user-supplied sub-pixel interpolation function",
        "error in user-supplied 1-d sub-pixel interpolation kernel",
        "invalid comma in expression",
        "invalid constant in expression",
        "duplicate variable name",
        "invalid number of transformation functions",
        "missing or invalid operand in expression",
        "missing or invalid operator in expression",
        "missing variable name",
        "missing left parenthesis in expression",
        "missing right parenthesis in expression",
        "missing right hand side in function",
        "undefined variable or function in expression",
        "variable name invalid",
        "wrong number of function arguments in expression",
        "invalid units specification",
        "no rest frequency is defined",
        "no standard of rest is defined",
        "invalid SpecMap",
        "invalid XML name or prefix",
        "invalid XML comment text",
        "invalid XML processing instruction target text",
        "invalid XML content item index",
        "supplied XML document is not well formed",
        "Range of log axis scale includes zero",
        "Invalid parameters for offset sky coordinate system",
        "error getting a named value from a KeyMap",
        "invalid integer index supplied for a KeyMap entry",
        "region cannot be re-centred",
        "attribute has no usable value",
        "incompatible time scales",
        "invalid TimeMap",
        "cannot use supplied AstroCoords info",
        "invalid AstroCoords index",
        "cannot conserve flux whilst resampling an array of data",
        "Unknown AST tuning parameter name supplied",
        "Bad value supplied for a public function parameter",
        "Supplied FrameSet does not contain any independent axes",
        "Attempt to delete original Plot3D base Frame",
        "Illegal syntax for string substitution template",
        "Incompatible flags for re-sampling or re-binning",
        "Error locking or unlocking an AST Object",
        "FITS keyword had undefined value",
        "invalid integer index supplied for a KeyMap vector element",
        "operation specifier invalid",
        "no inside point found",
        "requested key not found in KeyMap",
        "error putting a named value into a KeyMap",
        "Attempt made to add an entry to a locked KeyMap",
        "Bad data type",
        "Column already exists with different properties",
        "Bad null value for a FITS table column",
        "Key string is too long",
        "No such column exists in the table",
        "Table is too large",
        "Invalid array size",
        "Error reading WCS from FITS binary table",
        "Cannot access FITS binary table",
        "Error in levmar Levenberg-Marquardt code",
        "Fit failed",
        "A transformation generated one or more NaN values",
        "write error",
        "Bad variant Mapping name",
        "Attempt to add a variant Mapping to a mirror Frame",
        "Error in cminpack Levenberg-Marquardt code",
        "Supplied array has too many pixels",
        "No mapping found between coordinate systems",
        "Attempt to change an immutable attribute",
        "No bounding box available",
        "Supplied Frame has no sky coordinate axes",
        "Sky axes cannot be separated from other axes",
        "Wrong class of object supplied",
        "Class does not implement the invoked method",
        "Normalised MOC is too big",
        "Invalid argument value supplied to an AST method",
        "Invalid string-encoded MOC",
        "Supplied buffer too small",
        "Word is too long",
        "Array is too big",
        "Requested feature is not supported",
        "Text truncated",
        "Error in libyaml function",
        "Error reading YAML text",
        "AST was configured without YAML support",
        "No TMapping found",
        "Invalid YAML object supplied",
        "Invalid ASDF object supplied",
        "Unsupported ASDF object supplied",
    };
    // END GENERATED ERROR CODES
}
