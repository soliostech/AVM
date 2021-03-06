package org.aion.avm.core.types;

import java.io.IOException;
import java.util.Map;

import org.aion.avm.core.ClassHierarchyForest;
import org.aion.avm.core.dappreading.LoadedJar;


/**
 * Represents the original code submitted by the user, prior to validation or transformation.
 * Once transformed, the code this contains is moved into a TransformedDappModule.
 * All fields are public since this object is effectively an immutable struct.
 * See issue-134 for more details on this design.
 */
public class RawDappModule {
    /**
     * Reads the Dapp module from JAR bytes, in memory.
     * Note that a Dapp module is expected to specify a main class and contain at least one class.
     * 
     * @param jar The JAR bytes.
     * @return The module, or null if the contents of the JAR were insufficient for a Dapp.
     * @throws IOException An error occurred while reading the JAR contents.
     */
    public static RawDappModule readFromJar(byte[] jar) throws IOException {
        LoadedJar loadedJar = LoadedJar.fromBytes(jar);
        ClassHierarchyForest forest = ClassHierarchyForest.createForestFrom(loadedJar);
        Map<String, byte[]> classes = loadedJar.classBytesByQualifiedNames;
        String mainClass = loadedJar.mainClassName;
        // To be a valid Dapp, this must specify a main class and have at least one class.
        return ((null != mainClass) && !classes.isEmpty())
                ? new RawDappModule(classes, mainClass, forest, jar.length, classes.size())
                : null;
    }


    public final Map<String, byte[]> classes;
    public final String mainClass;
    public final ClassHierarchyForest classHierarchyForest;

    // For billing purpose
    public final long bytecodeSize;
    public final long numberOfClasses;
    
    private RawDappModule(Map<String, byte[]> classes, String mainClass, ClassHierarchyForest classHierarchyForest, long bytecodeSize, long numberOfClasses) {
        this.classes = classes;
        this.mainClass = mainClass;
        this.classHierarchyForest = classHierarchyForest;
        this.bytecodeSize = bytecodeSize;
        this.numberOfClasses = numberOfClasses;
    }
}
