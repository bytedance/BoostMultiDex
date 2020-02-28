package com.bytedance.boost_multidex;

import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import dalvik.system.DexFile;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/3/28.
 */
abstract class DexLoader {
    ElementConstructor mElementConstructor;

    static DexLoader create(int sdkInt) {
        if (sdkInt >= 19) {
            return new V19();
        } else if (sdkInt >= 14) {
            return new V14();
        } else {
            throw new UnsupportedOperationException(
                    "only support SDK_INT >= 14, give up when < 14");
        }
    }

    void install(ClassLoader loader, List<DexHolder> dexHolderList, SharedPreferences preferences) throws Exception {
        Field pathListField = Utility.findFieldRecursively(loader.getClass(), "pathList");
        Object dexPathList = pathListField.get(loader);

        Object[] elements = makeDexElements(dexHolderList, preferences);
        Utility.expandFieldArray(dexPathList, "dexElements", elements);
    }

    void install(ClassLoader loader, List<DexHolder> dexHolderList) throws Exception {
        Field pathListField = Utility.findFieldRecursively(loader.getClass(), "pathList");
        Object dexPathList = pathListField.get(loader);

        ArrayList<Object> elements = new ArrayList<>();
        for (int i = 0; i < dexHolderList.size(); ++i) {
            DexHolder dexHolder = dexHolderList.get(i);
            elements.add(dexHolder.toDexListElement(mElementConstructor));
            Monitor.get().logInfo("Install holder: " + dexHolder.getClass().getName() + ", index " + i);
        }

        Utility.expandFieldArray(dexPathList, "dexElements", elements.toArray());
    }

    /**
     * An emulation of {@code private static final dalvik.system.DexPathList#makeDexElements}
     * accepting only extracted secondary dex files.
     * OS version is catching IOException and just logging some of them, this version is letting
     * them through.
     */
    private Object[] makeDexElements(List<DexHolder> dexHolderList, SharedPreferences preferences) throws Exception {
        ArrayList<Object> elements = new ArrayList<>();

        for (int i = 0; i < dexHolderList.size(); ++i) {
            DexHolder dexHolder = dexHolderList.get(i);

            Object element = dexHolder.toDexListElement(mElementConstructor);
            while (element == null && dexHolder != null) {
                Monitor.get().logWarning("Load faster dex in holder " + dexHolder.toString());
                dexHolder = dexHolder.toFasterHolder(preferences);
                if (dexHolder != null) {
                    element = dexHolder.toDexListElement(mElementConstructor);
                }
            }

            if (element != null) {
                Monitor.get().logInfo("Load dex in holder " + dexHolder.toString());
                dexHolderList.set(i, dexHolder);
                elements.add(element);
            } else {
                throw new RuntimeException("Fail to load dex, index is " + i);
            }

            String dexInfo = dexHolder.toString();
            Result.get().addDexInfo(dexInfo);
            Monitor.get().logInfo("Add info: " + dexInfo);
        }

        return elements.toArray();
    }

    /**
     * A wrapper around
     * {@code private static final dalvik.system.DexPathList#makeDexElements}.
     */
    private static class V19 extends DexLoader {
        private V19() {
            try {
                Class<?> elementClass = Class.forName("dalvik.system.DexPathList$Element");
                mElementConstructor = new KKElementConstructor(elementClass);
            } catch (Throwable tr) {
                Monitor.get().logError("fail to get Element constructor", tr);
            }
        }
    }

    /**
     * Installer for platform versions 14, 15, 16, 17 and 18.
     */
    private static class V14 extends DexLoader {
        private V14() {
            ElementConstructor constructor = null;
            Class<?> elementClass;
            try {
                elementClass = Class.forName("dalvik.system.DexPathList$Element");
            } catch (Exception e) {
                Monitor.get().logError("can not find DexPathList$Element", e);
                return;
            }

            try {
                constructor = new ICSElementConstructor(elementClass);
            } catch (Exception ignored) {
            }

            if (constructor == null) {
                try {
                    constructor = new JBMR11ElementConstructor(elementClass);
                } catch (Exception ignored) {
                }
            }

            if (constructor == null) {
                try {
                    constructor = new JBMR2ElementConstructor(elementClass);
                } catch (Exception ignored) {
                }
            }

            mElementConstructor = constructor;
        }
    }

    interface ElementConstructor {
        Object newInstance(File file, Object dex)
                throws IllegalArgumentException, InstantiationException,
                IllegalAccessException, InvocationTargetException, IOException;
    }

    /**
     * Applies for ICS and early JB (initial release and MR1).
     */
    private static class ICSElementConstructor implements ElementConstructor {
        private final Constructor<?> mConstructor;

        ICSElementConstructor(Class<?> elementClass)
                throws SecurityException, NoSuchMethodException {
            mConstructor =
                    elementClass.getConstructor(File.class, ZipFile.class, DexFile.class);
            mConstructor.setAccessible(true);
        }

        @Override
        public Object newInstance(File file, Object dex)
                throws IllegalArgumentException, InstantiationException,
                IllegalAccessException, InvocationTargetException, IOException {
            // it is no use to set zip of dex here, because apk has contained resources.
            return mConstructor.newInstance(file, null, dex);
        }
    }

    /**
     * Applies for some intermediate JB (MR1.1).
     * <p>
     * See Change-Id: I1a5b5d03572601707e1fb1fd4424c1ae2fd2217d
     */
    private static class JBMR11ElementConstructor implements ElementConstructor {
        private final Constructor<?> mConstructor;

        JBMR11ElementConstructor(Class<?> elementClass)
                throws SecurityException, NoSuchMethodException {
            mConstructor = elementClass
                    .getConstructor(File.class, File.class, DexFile.class);
            mConstructor.setAccessible(true);
        }

        @Override
        public Object newInstance(File file, Object dex)
                throws IllegalArgumentException, InstantiationException,
                IllegalAccessException, InvocationTargetException {
            return mConstructor.newInstance(file, null, dex);
        }
    }

    /**
     * Applies for latest JB (MR2).
     * <p>
     * See Change-Id: Iec4dca2244db9c9c793ac157e258fd61557a7a5d
     */
    private static class JBMR2ElementConstructor implements ElementConstructor {
        private final Constructor<?> mConstructor;

        JBMR2ElementConstructor(Class<?> elementClass)
                throws SecurityException, NoSuchMethodException {
            mConstructor = elementClass
                    .getConstructor(File.class, boolean.class, File.class, DexFile.class);
            mConstructor.setAccessible(true);
        }

        @Override
        public Object newInstance(File file, Object dex)
                throws IllegalArgumentException, InstantiationException,
                IllegalAccessException, InvocationTargetException {
            return mConstructor.newInstance(file, false, null, dex);
        }
    }

    private static class KKElementConstructor implements ElementConstructor {
        private final Constructor<?> mConstructor;

        KKElementConstructor(Class<?> elementClass)
                throws SecurityException, NoSuchMethodException {
            mConstructor = Utility.findConstructor(elementClass, File.class, boolean.class, File.class, DexFile.class);
            mConstructor.setAccessible(true);
        }

        @Override
        public Object newInstance(File file, Object dex) throws IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException, IOException {
            return mConstructor.newInstance(file, false, null, dex);
        }
    }

}
