package dev.codex.flymepicker;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class FolderAlbums implements IXposedHookLoadPackage {
    private static final String MEDIA_PACKAGE = "com.android.providers.media";
    private static final String PROVIDER_PACKAGE = MEDIA_PACKAGE + ".module";
    private static final String PICKER_PACKAGE = MEDIA_PACKAGE + ".photopicker";
    private static final String FOLDER_ID_PREFIX = "dev.codex.flymepicker.folder.v1:";
    private static final String PAGE_TOKEN = "android.provider.extra.PAGE_TOKEN";
    private static final String ALBUM_EXTRA = FolderAlbums.class.getName() + ".album";
    private static final int BASE64_FLAGS = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

    private static final String[] ALBUM_COLUMNS = {
            "id", "date_taken_millis", "display_name", "album_media_cover_id",
            "album_media_count", "authority"
    };
    private static final String[] FOLDER_MEDIA_COLUMNS = {
            "volume_name", "relative_path", "_id AS media_id",
            "COALESCE(datetaken, date_modified * 1000) AS taken_ms"
    };
    private static final String SHARED_FOLDERS = "volume_name IS NOT NULL AND volume_name <> ''"
            + " AND relative_path IS NOT NULL"
            + " AND lower(relative_path) NOT LIKE 'android/data/%'"
            + " AND lower(relative_path) NOT LIKE 'android/obb/%'"
            + " AND lower(relative_path) NOT LIKE 'android/sandbox/%'";
    private static final String GUARD_ID = new Folder(
            "external_primary", "Pictures/.codex-flyme-picker-filter-check/").id();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam packageParam) {
        if (!PROVIDER_PACKAGE.equals(packageParam.packageName)
                || !PROVIDER_PACKAGE.equals(packageParam.processName)) {
            return;
        }

        try {
            new ProviderHooks(packageParam.classLoader).install();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            logFailure("setup", error);
        }
    }

    private static final class ProviderHooks {
        private final Method queryAlbums;
        private final Method queryMedia;
        private final Method appendWhere;
        private final Method createMediaQueryBuilder;
        private final Method appendWhereStandalone;
        private final Field databaseHelper;
        private final Field volumeCache;
        private final Method runWithTransaction;
        private final Method volumePath;
        private final Method albumProviderQuery;
        private final Method mediaProviderQuery;
        private final Method mediaQueryBody;
        private final ThreadLocal<FilterCall> activeFilter = new ThreadLocal<>();
        private final ThreadLocal<Boolean> filterCanary = new ThreadLocal<>();
        private volatile boolean filterVerified;
        private volatile String lastVisibilitySummary;
        private boolean verificationAttempted;

        ProviderHooks(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> facade = Class.forName(PICKER_PACKAGE + ".data.ExternalDbFacade", false, loader);
            Class<?> provider = Class.forName(PICKER_PACKAGE + ".PhotoPickerProvider", false, loader);
            Class<?> database = Class.forName(MEDIA_PACKAGE + ".DatabaseHelper", false, loader);
            Class<?> volumes = Class.forName(MEDIA_PACKAGE + ".VolumeCache", false, loader);
            queryAlbums = uniqueMethod(facade, "album query", Cursor.class, false, String[].class);
            queryMedia = uniqueMethod(facade, "media query", Cursor.class, false,
                    long.class, String.class, String[].class, int.class, String.class);
            appendWhere = uniqueMethod(facade, "media filters", List.class, true,
                    SQLiteQueryBuilder.class, String.class, String[].class);
            createMediaQueryBuilder = uniqueMethod(facade, "media query builder",
                    SQLiteQueryBuilder.class, false);
            databaseHelper = uniqueField(facade, database);
            volumeCache = uniqueField(facade, volumes);
            volumePath = uniqueMethod(volumes, "volume path", File.class, false,
                    String.class, UserHandle.class);

            // These names distinguish operations that share the same parameter and return types.
            appendWhereStandalone = namedMethod(SQLiteQueryBuilder.class, "appendWhereStandalone",
                    void.class, CharSequence.class);
            runWithTransaction = namedMethod(database, "runWithTransaction", Object.class,
                    Function.class);
            albumProviderQuery = namedMethod(provider, "onQueryAlbums", Cursor.class, Bundle.class);
            mediaProviderQuery = namedMethod(provider, "onQueryMedia", Cursor.class, Bundle.class);
            Class<?>[] transactionArguments = {List.class, long.class, String.class, String.class,
                    String[].class, String.class, int.class, SQLiteDatabase.class};
            mediaQueryBody = uniqueMethod(facade, "media transaction", candidate ->
                    candidate.isSynthetic() && !Modifier.isStatic(candidate.getModifiers())
                            && candidate.getReturnType() == Cursor.class
                            && Arrays.equals(candidate.getParameterTypes(), transactionArguments));
        }

        void install() throws ReflectiveOperationException {
            Method deoptimize = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
            // The Flyme DEX calls appendWhere inside the transaction lambda.
            deoptimize.invoke(null, mediaQueryBody);
            deoptimize.invoke(null, queryMedia);
            deoptimize.invoke(null, mediaProviderQuery);
            deoptimize.invoke(null, albumProviderQuery);

            List<XC_MethodHook.Unhook> installed = new ArrayList<>();
            try {
                installed.add(XposedBridge.hookMethod(queryMedia, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String albumId = (String) param.args[1];
                        if (isFolderId(albumId)) {
                            Folder folder = Folder.decode(albumId);
                            boolean checking = GUARD_ID.equals(albumId) && filterCanary.get() != null;
                            if (folder != null && !checking && !folderVisible(param.thisObject, folder)) {
                                folder = null;
                            }
                            FilterCall call = new FilterCall(albumId, folder, activeFilter.get());
                            activeFilter.set(call);
                            param.setObjectExtra(ALBUM_EXTRA, call);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        finishMediaQuery(param);
                    }
                }));
                installed.add(XposedBridge.hookMethod(appendWhere, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String albumId = (String) param.args[1];
                        if (isFolderId(albumId)) {
                            param.setObjectExtra(ALBUM_EXTRA, albumId);
                            // Keep the original MIME filter without its unknown album fallback.
                            param.args[1] = null;
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        applyFolderFilter(param);
                    }
                }));
                installed.add(XposedBridge.hookMethod(queryAlbums, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        addFolderAlbums(param);
                    }
                }));
            } catch (RuntimeException | LinkageError error) {
                for (int index = installed.size() - 1; index >= 0; index--) {
                    installed.get(index).unhook();
                }
                throw error;
            }
            XposedBridge.log("FlymeFolderAlbums: MediaProvider hooks installed, transaction="
                    + mediaQueryBody.getName());
        }

        private void applyFolderFilter(XC_MethodHook.MethodHookParam param) {
            String albumId = (String) param.getObjectExtra(ALBUM_EXTRA);
            if (albumId == null || param.hasThrowable()) {
                return;
            }

            FilterCall call = activeFilter.get();
            SQLiteQueryBuilder builder = (SQLiteQueryBuilder) param.args[0];
            if (call == null || !albumId.equals(call.albumId) || call.folder == null) {
                appendClause(builder, "0");
                return;
            }

            @SuppressWarnings("unchecked")
            List<String> args = new ArrayList<>((List<String>) param.getResult());
            args.add(call.folder.volume);
            args.add(call.folder.path);
            appendClause(builder, "volume_name = ? AND relative_path = ?");
            param.setResult(args);
            call.applied = true;
        }

        private void finishMediaQuery(XC_MethodHook.MethodHookParam param) {
            FilterCall call = (FilterCall) param.getObjectExtra(ALBUM_EXTRA);
            if (call == null) {
                return;
            }
            if (call.previous == null) {
                activeFilter.remove();
            } else {
                activeFilter.set(call.previous);
            }

            if (param.hasThrowable() || !(param.getResult() instanceof Cursor)) {
                return;
            }
            if (call.applied) {
                if (GUARD_ID.equals(call.albumId) && filterCanary.get() != null) {
                    filterCanary.set(true);
                }
                return;
            }

            // Replace even a successful stock cursor if its directory predicate was not applied.
            try (Cursor unfiltered = (Cursor) param.getResult()) {
                MatrixCursor empty = new MatrixCursor(unfiltered.getColumnNames(), 0);
                Bundle extras = new Bundle(unfiltered.getExtras());
                extras.remove(PAGE_TOKEN);
                empty.setExtras(extras);
                param.setResult(empty);
            } catch (RuntimeException error) {
                param.setResult(null);
            }
        }

        private void addFolderAlbums(XC_MethodHook.MethodHookParam param) {
            if (param.hasThrowable() || !(param.getResult() instanceof MatrixCursor)) {
                return;
            }
            MatrixCursor albums = (MatrixCursor) param.getResult();
            if (!Arrays.equals(ALBUM_COLUMNS, albums.getColumnNames())) {
                return;
            }

            try {
                if (!verifyFilter(param.thisObject)) {
                    return;
                }
                List<Album> folders = listFolders(param.thisObject, (String[]) param.args[0]);
                Set<String> volumes = new HashSet<>();
                for (Album album : folders) {
                    volumes.add(album.folder.volume);
                }
                VisibleDirectories visibility = visibleDirectories(param.thisObject, volumes);
                int candidates = folders.size();
                folders.removeIf(album -> !visibility.includes(album.folder));
                String summary = visibility.summary(candidates, folders.size());
                if (!summary.equals(lastVisibilitySummary)) {
                    lastVisibilitySummary = summary;
                    XposedBridge.log("FlymeFolderAlbums: " + summary);
                }
                Map<String, Integer> labels = new HashMap<>();
                for (Album album : folders) {
                    labels.merge(album.folder.label(), 1, Integer::sum);
                }
                for (Album album : folders) {
                    Folder folder = album.folder;
                    String label = folder.label();
                    if (labels.get(label) > 1) {
                        label += " (" + folder.volume + "/" + folder.path + ")";
                    }
                    albums.addRow(new Object[] {
                            folder.id(), album.date, label, album.coverId, album.count, PICKER_PACKAGE
                    });
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                logFailure("album query", error);
            }
        }

        private synchronized boolean verifyFilter(Object facade) throws ReflectiveOperationException {
            if (!verificationAttempted) {
                verificationAttempted = true;
                filterCanary.set(false);
                try (Cursor cursor = (Cursor) queryMedia.invoke(facade, -1L, GUARD_ID, null, 1, null)) {
                    filterVerified = Boolean.TRUE.equals(filterCanary.get())
                            && cursor != null && cursor.getCount() == 0;
                } finally {
                    filterCanary.remove();
                }
                XposedBridge.log("FlymeFolderAlbums: directory filter verified=" + filterVerified);
            }
            return filterVerified;
        }

        @SuppressWarnings("unchecked")
        private List<Album> listFolders(Object facade, String[] mimeTypes)
                throws ReflectiveOperationException {
            Object helper = databaseHelper.get(facade);
            Function<SQLiteDatabase, List<Album>> transaction = db -> queryFolders(db, facade, mimeTypes);
            return (List<Album>) runWithTransaction.invoke(helper, transaction);
        }

        private List<Album> queryFolders(SQLiteDatabase db, Object facade, String[] mimeTypes) {
            try {
                SQLiteQueryBuilder builder = (SQLiteQueryBuilder) createMediaQueryBuilder.invoke(facade);
                @SuppressWarnings("unchecked")
                List<String> args = (List<String>) appendWhere.invoke(null, builder, null, mimeTypes);
                String eligible = builder.buildQuery(FOLDER_MEDIA_COLUMNS, SHARED_FOLDERS,
                        null, null, null, null);
                String sql = "SELECT volume_name, relative_path, media_id, taken_ms, media_count FROM ("
                        + "SELECT volume_name, relative_path, media_id, taken_ms, "
                        + "COUNT(*) OVER (PARTITION BY volume_name, relative_path) AS media_count, "
                        + "ROW_NUMBER() OVER (PARTITION BY volume_name, relative_path "
                        + "ORDER BY taken_ms DESC, media_id DESC) AS folder_rank FROM (" + eligible
                        + ")) WHERE folder_rank = 1 ORDER BY taken_ms DESC, media_id DESC";
                List<Album> folders = new ArrayList<>();
                try (Cursor cursor = db.rawQuery(sql, args.toArray(new String[0]))) {
                    while (cursor.moveToNext()) {
                        Folder folder = Folder.create(cursor.getString(0), cursor.getString(1));
                        if (folder != null) {
                            folders.add(new Album(folder, cursor.getString(2), cursor.getLong(3),
                                    cursor.getLong(4)));
                        }
                    }
                }
                return folders;
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        }

        private boolean folderVisible(Object facade, Folder folder) {
            try {
                return visibleDirectories(facade, Collections.singleton(folder.volume)).includes(folder);
            } catch (ReflectiveOperationException | RuntimeException error) {
                return false;
            }
        }

        private VisibleDirectories visibleDirectories(Object facade, Set<String> names)
                throws IllegalAccessException {
            Object cache = volumeCache.get(facade);
            Map<String, File> roots = new HashMap<>();
            int canonicalErrors = 0;
            UserHandle user = Process.myUserHandle();
            for (String name : names) {
                File directory = null;
                try {
                    directory = (File) volumePath.invoke(cache, name, user);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    // Flyme can report an empty volume cache inside the picker query.
                }
                if ((directory == null || !directory.isAbsolute()) && "external_primary".equals(name)) {
                    directory = new File("/storage/emulated", Integer.toString(user.getIdentifier()));
                }
                if (directory == null || !directory.isAbsolute()) {
                    continue;
                }
                try {
                    directory = directory.getCanonicalFile();
                } catch (IOException error) {
                    // Check the resolved absolute root directly when canonicalization fails.
                    canonicalErrors++;
                }
                roots.put(name, directory);
            }
            return new VisibleDirectories(roots, names.size(), canonicalErrors);
        }

        private void appendClause(SQLiteQueryBuilder builder, String clause) {
            try {
                appendWhereStandalone.invoke(builder, clause);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    private static final class VisibleDirectories {
        private final Map<String, File> roots;
        private final Map<File, Boolean> checked = new HashMap<>();
        private final Map<Integer, Integer> rootErrors = new TreeMap<>();
        private final Map<Integer, Integer> directoryErrors = new TreeMap<>();
        private final Map<Integer, Integer> markerErrors = new TreeMap<>();
        private final int volumes;
        private final int canonicalErrors;
        private int missingRoots;
        private int nonDirectories;
        private int markers;

        VisibleDirectories(Map<String, File> roots, int volumes, int canonicalErrors) {
            this.roots = roots;
            this.volumes = volumes;
            this.canonicalErrors = canonicalErrors;
        }

        boolean includes(Folder folder) {
            File directory = roots.get(folder.volume);
            if (directory == null) {
                missingRoots++;
                return false;
            }
            if (!checked.computeIfAbsent(directory, file -> isVisible(file, rootErrors))) {
                return false;
            }
            if (!folder.path.isEmpty() && !"/".equals(folder.path)) {
                for (String part : folder.path.split("/")) {
                    directory = new File(directory, part);
                    if (!checked.computeIfAbsent(directory, file -> isVisible(file, directoryErrors))) {
                        return false;
                    }
                }
            }
            return true;
        }

        String summary(int candidates, int visible) {
            return "album visibility candidates=" + candidates + " visible=" + visible
                    + " volumes=" + volumes + " roots=" + roots.size() + " missingRoots=" + missingRoots
                    + " canonicalErrors=" + canonicalErrors + " nonDirectories=" + nonDirectories
                    + " markers=" + markers + " rootErrno=" + rootErrors
                    + " directoryErrno=" + directoryErrors + " markerErrno=" + markerErrors;
        }

        private boolean isVisible(File directory, Map<Integer, Integer> statErrors) {
            try {
                // Reject symlinks so a relative media path cannot escape its volume or ancestors.
                if (!OsConstants.S_ISDIR(Os.lstat(directory.getPath()).st_mode)) {
                    nonDirectories++;
                    return false;
                }
            } catch (ErrnoException error) {
                statErrors.merge(error.errno, 1, Integer::sum);
                return false;
            }
            try {
                Os.lstat(new File(directory, ".nomedia").getPath());
                markers++;
                return false;
            } catch (ErrnoException error) {
                if (error.errno == OsConstants.ENOENT) {
                    return true;
                }
                markerErrors.merge(error.errno, 1, Integer::sum);
                return false;
            }
        }
    }

    private static final class FilterCall {
        final String albumId;
        final Folder folder;
        final FilterCall previous;
        boolean applied;

        FilterCall(String albumId, Folder folder, FilterCall previous) {
            this.albumId = albumId;
            this.folder = folder;
            this.previous = previous;
        }
    }

    private static final class Album {
        final Folder folder;
        final String coverId;
        final long date;
        final long count;

        Album(Folder folder, String coverId, long date, long count) {
            this.folder = folder;
            this.coverId = coverId;
            this.date = date;
            this.count = count;
        }
    }

    private static final class Folder {
        final String volume;
        final String path;

        Folder(String volume, String path) {
            this.volume = volume;
            this.path = path;
        }

        static Folder create(String volume, String path) {
            if (volume == null || volume.isEmpty() || volume.contains("/") || path == null) {
                return null;
            }
            if (path.isEmpty() || "/".equals(path)) {
                return new Folder(volume, path);
            }
            String lower = path.toLowerCase(Locale.ROOT);
            if (path.startsWith("/") || path.indexOf('\u0000') >= 0 || !path.endsWith("/")
                    || lower.startsWith("android/data/") || lower.startsWith("android/obb/")
                    || lower.startsWith("android/sandbox/")) {
                return null;
            }
            for (String part : path.substring(0, path.length() - 1).split("/", -1)) {
                if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                    return null;
                }
            }
            return new Folder(volume, path);
        }

        static Folder decode(String id) {
            int start = FOLDER_ID_PREFIX.length();
            int separator = id.indexOf('.', start);
            if (separator <= start || id.length() > 8192) {
                return null;
            }
            try {
                Folder folder = create(unpack(id.substring(start, separator)),
                        unpack(id.substring(separator + 1)));
                return folder != null && folder.id().equals(id) ? folder : null;
            } catch (IllegalArgumentException error) {
                return null;
            }
        }

        String id() {
            return FOLDER_ID_PREFIX + pack(volume) + "." + pack(path);
        }

        String label() {
            if (path.length() <= 1) {
                return "/";
            }
            int end = path.length() - 1;
            return path.substring(path.lastIndexOf('/', end - 1) + 1, end);
        }

        private static String pack(String value) {
            return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), BASE64_FLAGS);
        }

        private static String unpack(String value) {
            return new String(Base64.decode(value, BASE64_FLAGS), StandardCharsets.UTF_8);
        }
    }

    private static Method namedMethod(Class<?> owner, String name, Class<?> result,
            Class<?>... arguments) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, arguments);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(owner.getName() + "." + name);
        }
        method.setAccessible(true);
        return method;
    }

    private static Method uniqueMethod(Class<?> owner, String role, Class<?> result, boolean isStatic,
            Class<?>... arguments) throws NoSuchMethodException {
        return uniqueMethod(owner, role, candidate -> !candidate.isSynthetic()
                && candidate.getReturnType() == result
                && Modifier.isStatic(candidate.getModifiers()) == isStatic
                && Arrays.equals(candidate.getParameterTypes(), arguments));
    }

    private static Method uniqueMethod(Class<?> owner, String role, Predicate<Method> matches)
            throws NoSuchMethodException {
        Method match = null;
        for (Method candidate : owner.getDeclaredMethods()) {
            if (matches.test(candidate)) {
                if (match != null) {
                    throw new NoSuchMethodException(owner.getName() + " has multiple matches for " + role);
                }
                match = candidate;
            }
        }
        if (match == null) {
            throw new NoSuchMethodException(owner.getName() + " has no match for " + role);
        }
        match.setAccessible(true);
        return match;
    }

    private static Field uniqueField(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        Field match = null;
        for (Field candidate : owner.getDeclaredFields()) {
            if (candidate.getType() == type && !Modifier.isStatic(candidate.getModifiers())) {
                if (match != null) {
                    throw new NoSuchFieldException(owner.getName() + " has multiple fields of type " + type.getName());
                }
                match = candidate;
            }
        }
        if (match == null) {
            throw new NoSuchFieldException(owner.getName() + " has no field of type " + type.getName());
        }
        match.setAccessible(true);
        return match;
    }

    private static boolean isFolderId(String id) {
        return id != null && id.startsWith(FOLDER_ID_PREFIX);
    }

    private static void logFailure(String operation, Throwable error) {
        if (error instanceof InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        XposedBridge.log("FlymeFolderAlbums: " + operation + " failed ("
                + error.getClass().getSimpleName() + ")");
    }
}
