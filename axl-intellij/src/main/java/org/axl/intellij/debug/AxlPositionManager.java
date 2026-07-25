package org.axl.intellij.debug;

import com.intellij.debugger.NoDataException;
import com.intellij.debugger.PositionManager;
import com.intellij.debugger.SourcePosition;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.requests.ClassPrepareRequestor;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.request.ClassPrepareRequest;
import java.util.List;
import java.util.Set;
import org.axl.intellij.AxlFileType;
import org.axl.intellij.psi.AxlFile;
import org.jetbrains.annotations.NotNull;

/**
 * Maps between .axl lines and bytecode through the JSR-45 stratum `Axolotl` that the build adds to
 * generated classes. The class files already carry .axl lines, so the stratum maps each line to
 * itself; asking for it, rather than for the default stratum, keeps classes built by older versions
 * (which have generated Java lines) working. Stepping stays on an .axl line until the debugger
 * reaches another one, because the debugger compares positions this manager returns.
 */
final class AxlPositionManager implements PositionManager {

    private static final String STRATUM = "Axolotl";

    private final DebugProcess process;

    AxlPositionManager(DebugProcess process) {
        this.process = process;
    }

    /** A place in an .axl file; the path is the one in the SMAP, e.g. `app/Main.axl`. */
    record AxlLocation(String path, int line) {}

    @Override
    public SourcePosition getSourcePosition(Location location) throws NoDataException {
        AxlLocation axl = axlLocation(location);
        String className = location.declaringType().name();
        PsiFile file = ReadAction.compute(() -> findFile(axl.path(), className));
        if (file == null) {
            throw NoDataException.INSTANCE;
        }
        return SourcePosition.createFromLine(file, axl.line() - 1);
    }

    static AxlLocation axlLocation(Location location) throws NoDataException {
        if (location == null || !location.declaringType().availableStrata().contains(STRATUM)) {
            throw NoDataException.INSTANCE;
        }
        int line = location.lineNumber(STRATUM);
        try {
            if (line <= 0) {
                throw NoDataException.INSTANCE;
            }
            return new AxlLocation(location.sourcePath(STRATUM), line);
        } catch (AbsentInformationException e) {
            throw NoDataException.INSTANCE;
        }
    }

    /**
     * The .axl file with the SMAP's source path, e.g. `app/Main.axl`. A path without a directory
     * names a file in the package of the class.
     */
    private PsiFile findFile(String path, String className) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String packageName = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
        GlobalSearchScope scope = GlobalSearchScope.allScope(process.getProject());
        for (VirtualFile file : FilenameIndex.getVirtualFilesByName(name, scope)) {
            PsiFile psi = PsiManager.getInstance(process.getProject()).findFile(file);
            boolean matches = path.contains("/")
                    ? file.getPath().endsWith("/" + path) || file.getPath().equals(path)
                    : psi instanceof AxlFile axl && axl.packageName().equals(packageName);
            if (matches) {
                return psi;
            }
        }
        return null;
    }

    @Override
    public @NotNull List<ReferenceType> getAllClasses(@NotNull SourcePosition position) throws NoDataException {
        String fileClass = className(position);
        return process.getVirtualMachineProxy().allClasses().stream()
                .filter(type -> isCompiledFrom(type.name(), fileClass))
                .toList();
    }

    /** Classes declared in an .axl file are nested in its file class, e.g. `app.Shapes$Counter`. */
    static boolean isCompiledFrom(String className, String fileClass) {
        return className.equals(fileClass) || className.startsWith(fileClass + "$");
    }

    @Override
    public @NotNull List<Location> locationsOfLine(@NotNull ReferenceType type, @NotNull SourcePosition position)
            throws NoDataException {
        AxlFile file = axlFile(position);
        if (!isCompiledFrom(type.name(), className(position))) {
            return List.of();
        }
        try {
            return type.locationsOfLine(STRATUM, file.getName(), position.getLine() + 1);
        } catch (AbsentInformationException e) {
            throw NoDataException.INSTANCE;
        }
    }

    @Override
    public ClassPrepareRequest createPrepareRequest(@NotNull ClassPrepareRequestor requestor, @NotNull SourcePosition position)
            throws NoDataException {
        // The pattern also matches unrelated classes such as `app.MainTest`; locationsOfLine skips them.
        return process.getRequestsManager().createClassPrepareRequest(requestor, className(position) + "*");
    }

    @Override
    public @NotNull Set<? extends FileType> getAcceptedFileTypes() {
        return Set.of(AxlFileType.INSTANCE);
    }

    private static String className(SourcePosition position) throws NoDataException {
        AxlFile file = axlFile(position);
        return ReadAction.compute(file::qualifiedClassName);
    }

    private static AxlFile axlFile(SourcePosition position) throws NoDataException {
        if (position.getFile() instanceof AxlFile file) {
            return file;
        }
        throw NoDataException.INSTANCE;
    }
}
