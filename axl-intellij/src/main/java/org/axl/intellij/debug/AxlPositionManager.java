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
 * generated classes. Stepping stays on an .axl line until the debugger reaches another one, because
 * the debugger compares positions this manager returns.
 */
final class AxlPositionManager implements PositionManager {

    private static final String STRATUM = "Axolotl";

    private final DebugProcess process;

    AxlPositionManager(DebugProcess process) {
        this.process = process;
    }

    @Override
    public SourcePosition getSourcePosition(Location location) throws NoDataException {
        if (location == null || !location.declaringType().availableStrata().contains(STRATUM)) {
            throw NoDataException.INSTANCE;
        }
        int line = location.lineNumber(STRATUM);
        String path;
        try {
            path = location.sourcePath(STRATUM);
        } catch (AbsentInformationException e) {
            throw NoDataException.INSTANCE;
        }
        PsiFile file = ReadAction.compute(() -> findFile(path));
        if (file == null || line <= 0) {
            throw NoDataException.INSTANCE;
        }
        return SourcePosition.createFromLine(file, line - 1);
    }

    /** The .axl file with the SMAP's source path, e.g. `app/Main.axl`. */
    private PsiFile findFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        GlobalSearchScope scope = GlobalSearchScope.allScope(process.getProject());
        for (VirtualFile file : FilenameIndex.getVirtualFilesByName(name, scope)) {
            if (file.getPath().endsWith("/" + path) || file.getPath().equals(path) || !path.contains("/")) {
                return PsiManager.getInstance(process.getProject()).findFile(file);
            }
        }
        return null;
    }

    @Override
    public @NotNull List<ReferenceType> getAllClasses(@NotNull SourcePosition position) throws NoDataException {
        return process.getVirtualMachineProxy().classesByName(className(position));
    }

    @Override
    public @NotNull List<Location> locationsOfLine(@NotNull ReferenceType type, @NotNull SourcePosition position)
            throws NoDataException {
        AxlFile file = axlFile(position);
        try {
            return type.locationsOfLine(STRATUM, file.getName(), position.getLine() + 1);
        } catch (AbsentInformationException e) {
            throw NoDataException.INSTANCE;
        }
    }

    @Override
    public ClassPrepareRequest createPrepareRequest(@NotNull ClassPrepareRequestor requestor, @NotNull SourcePosition position)
            throws NoDataException {
        return process.getRequestsManager().createClassPrepareRequest(requestor, className(position));
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
