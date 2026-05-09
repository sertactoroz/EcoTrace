package com.ecotrace.api.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class ModuleBoundaryTest {

    private static final String[] MODULES = {
            "identity", "profile", "waste", "collection", "gamification",
            "leaderboard", "media", "notification", "moderation", "analytics"
    };

    private static final JavaClasses IMPORTED = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.ecotrace.api");

    @Test
    void entities_are_module_private() {
        for (String m : MODULES) {
            ArchRule rule = classes()
                    .that().resideInAPackage("com.ecotrace.api." + m + ".entity..")
                    .should().onlyHaveDependentClassesThat()
                    .resideInAnyPackage(
                            "com.ecotrace.api." + m + "..",
                            "com.ecotrace.api.common..")
                    .as("Entities in module '" + m + "' must only be used inside the module or by common")
                    .allowEmptyShould(true);
            rule.check(IMPORTED);
        }
    }

    @Test
    void repositories_are_module_private() {
        for (String m : MODULES) {
            ArchRule rule = classes()
                    .that().resideInAPackage("com.ecotrace.api." + m + ".repository..")
                    .should().onlyHaveDependentClassesThat()
                    .resideInAPackage("com.ecotrace.api." + m + "..")
                    .as("Repositories in module '" + m + "' must not be used outside the module")
                    .allowEmptyShould(true);
            rule.check(IMPORTED);
        }
    }

    @Test
    void services_are_module_private() {
        for (String m : MODULES) {
            ArchRule rule = classes()
                    .that().resideInAPackage("com.ecotrace.api." + m + ".service..")
                    .should().onlyHaveDependentClassesThat()
                    .resideInAnyPackage(
                            "com.ecotrace.api." + m + "..",
                            "com.ecotrace.api.common..")
                    .as("Services in module '" + m + "' must not be called from other modules directly")
                    .allowEmptyShould(true);
            rule.check(IMPORTED);
        }
    }
}
