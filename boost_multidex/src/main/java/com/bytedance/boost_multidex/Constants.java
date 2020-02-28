package com.bytedance.boost_multidex;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/3/3.
 */
interface Constants {
    String TAG = "BoostMultiDex";

    String CODE_CACHE_SECONDARY_FOLDER_NAME = "code_cache/secondary-dexes";

    String BOOST_MULTIDEX_DIR_NAME = "boost_multidex";

    String DEX_DIR_NAME = "dex_cache";

    String ODEX_DIR_NAME = "odex_cache";

    String ZIP_DIR_NAME = "zip_cache";

    int MIN_SDK_VERSION = 14;

    int VM_WITH_MULTIDEX_VERSION_MAJOR = 2;

    int VM_WITH_MULTIDEX_VERSION_MINOR = 1;

    long SPACE_THRESHOLD = 150_000_000L;

    long SPACE_MIN_THRESHOLD = 20_000_000L;

    long MEM_THRESHOLD = 128_000_000L;

    /**
     * We look for additional dex files named {@code classes2.dex},
     * {@code classes3.dex}, etc.
     */

    String DEX_PREFIX = "classes";
    String DEX_SUFFIX = ".dex";
    String ZIP_SUFFIX = ".zip";
    String ODEX_SUFFIX = ".odex";

    String EXTRACTED_NAME_EXT = ".classes";
    String EXTRACTED_SUFFIX = ".dex";
    int MAX_EXTRACT_ATTEMPTS = 3;
    int EXTRACTED_SUFFIX_LENGTH = EXTRACTED_SUFFIX.length();

    String PREFS_FILE = "boost_multidex.records";
    String KEY_TIME_STAMP = "timestamp";
    String KEY_CRC = "crc";
    String KEY_DEX_NUMBER = "dex.number";
    String KEY_DEX_CHECKSUM = "dex.checksum.";
    String KEY_DEX_TIME = "dex.time.";
    String KEY_ODEX_CHECKSUM = "odex.checksum.";
    String KEY_ODEX_TIME = "odex.time.";
    String KEY_DEX_OBJ_TYPE = "dex.obj.type";

    /**
     * Size of reading buffers.
     */
    int BUFFER_SIZE = 0x2000;
    /* Keep value away from 0 because it is a too probable time stamp value */
    long NO_VALUE = -1L;

    String LOCK_PREPARE_FILENAME = "boost_multidex.prepare.lock";
    String LOCK_INSTALL_FILENAME = "boost_multidex.install.lock";

    /* redefine those constant here because of bug 13721174 preventing to compile using the
     * constants defined in ZipFile */
    int ENDHDR = 22;
    int ENDSIG = 0x6054b50;

    int LOAD_TYPE_APK_BUF = 0;
    int LOAD_TYPE_DEX_BUF = 1;
    int LOAD_TYPE_DEX_OPT = 2;
    int LOAD_TYPE_ZIP_OPT = 3;
    int LOAD_TYPE_INVALID = 9;

    String LIB_YUNOS_PATH = "/system/lib/libvmkid_lemur.so";
}
