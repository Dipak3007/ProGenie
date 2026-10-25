package com.progenie;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Module boundaries (Spring Modulith). Each top-level package under com.progenie is a module; other modules may
 * use only its public API (the top-level package plus sub-packages marked with @NamedInterface) and its events.
 * No Spring context or database needed, so it runs with the unit tests.
 */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(ProGenieApplication.class);

    @Test
    void modulesOnlyUseEachOthersPublicApi() {
        MODULES.verify();
    }

    /** Writes the module diagram (PlantUML) and module canvases to target/spring-modulith-docs. */
    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml().writeModuleCanvases();
    }
}
