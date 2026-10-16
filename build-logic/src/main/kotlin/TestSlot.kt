import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A build service with a single slot that every test task uses, so only one test JVM (and its Testcontainers
 * containers) runs at a time even in a parallel build. See opsagent.java-conventions.
 */
abstract class TestSlot : BuildService<BuildServiceParameters.None>
