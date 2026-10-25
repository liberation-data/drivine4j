package org.drivine.codegen.java;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * The generated DSL compiles for each shape a view's fields can take: a view held by another, a
 * relationship fragment, a view that holds itself; and a static field is no property.
 */
class ViewShapesCodegenTest {

    private static final JavaFileObject PERSON = JavaFileObjects.forSourceLines("sample.vs.Person",
        "package sample.vs;",
        "import org.drivine.annotation.NodeFragment;",
        "import org.drivine.annotation.NodeId;",
        "@NodeFragment(labels = {\"Person\"})",
        "public class Person {",
        "  public static final String KIND = \"person\";",
        "  @NodeId public String id;",
        "  public String name;",
        "}");

    private static final JavaFileObject COMPANY = JavaFileObjects.forSourceLines("sample.vs.Company",
        "package sample.vs;",
        "import org.drivine.annotation.NodeFragment;",
        "import org.drivine.annotation.NodeId;",
        "@NodeFragment(labels = {\"Company\"})",
        "public class Company {",
        "  @NodeId public String id;",
        "  public String title;",
        "}");

    private static Compilation compile(JavaFileObject... sources) {
        JavaFileObject[] all = new JavaFileObject[sources.length + 2];
        all[0] = PERSON;
        all[1] = COMPANY;
        System.arraycopy(sources, 0, all, 2, sources.length);
        return Compiler.javac().withProcessors(new GraphViewProcessor()).compile(all);
    }

    private static JavaFileObject source(String name, String... lines) {
        return JavaFileObjects.forSourceLines("sample.vs." + name, lines);
    }

    @Test
    void aViewHeldByAnotherHasItsOwnProperties() {
        JavaFileObject employee = source("EmployeeView",
            "package sample.vs;",
            "import org.drivine.annotation.GraphRelationship;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class EmployeeView {",
            "  @Root public Person person;",
            "  @GraphRelationship(type = \"WORKS_FOR\") public Company employer;",
            "}");
        JavaFileObject team = source("TeamView",
            "package sample.vs;",
            "import java.util.List;",
            "import org.drivine.annotation.GraphRelationship;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class TeamView {",
            "  @Root public Company company;",
            "  @GraphRelationship(type = \"EMPLOYS\") public List<EmployeeView> staff;",
            "}");

        Compilation compilation = compile(employee, team);

        assertThat(compilation).succeeded();
        assertThat(compilation)
            .generatedSourceFile("sample.vs.EmployeeViewProperties")
            .contentsAsUtf8String()
            .contains("new CompanyProperties(alias + \"_employer\")");
        assertThat(compilation)
            .generatedSourceFile("sample.vs.EmployeeViewProperties")
            .contentsAsUtf8String()
            .contains("new PersonProperties(alias)");
    }

    @Test
    void aRelationshipFragmentHasPropertiesOfItsOwnFieldsButNotItsTarget() {
        JavaFileObject employment = source("Employment",
            "package sample.vs;",
            "import org.drivine.annotation.RelationshipFragment;",
            "@RelationshipFragment",
            "public class Employment {",
            "  public String role;",
            "  public Company target;",
            "}");
        JavaFileObject career = source("CareerView",
            "package sample.vs;",
            "import java.util.List;",
            "import org.drivine.annotation.GraphRelationship;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class CareerView {",
            "  @Root public Person person;",
            "  @GraphRelationship(type = \"WORKED_AT\") public List<Employment> history;",
            "}");

        Compilation compilation = compile(employment, career);

        assertThat(compilation).succeeded();
        assertThat(compilation)
            .generatedSourceFile("sample.vs.EmploymentProperties")
            .contentsAsUtf8String()
            .contains("\"role\"");
        assertThat(compilation)
            .generatedSourceFile("sample.vs.EmploymentProperties")
            .contentsAsUtf8String()
            .doesNotContain("target");
        assertThat(compilation).generatedSourceFile("sample.vs.CompanyProperties");
    }

    @Test
    void aViewThatHoldsItselfEnds() {
        JavaFileObject manager = source("ManagerView",
            "package sample.vs;",
            "import java.util.List;",
            "import org.drivine.annotation.GraphRelationship;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class ManagerView {",
            "  @Root public Person person;",
            "  @GraphRelationship(type = \"MANAGES\") public List<ManagerView> reports;",
            "}");

        Compilation compilation = compile(manager);

        assertThat(compilation).succeeded();
        assertThat(compilation)
            .generatedSourceFile("sample.vs.ManagerViewProperties")
            .contentsAsUtf8String()
            .contains("public ManagerViewProperties reports() {\n    return new ManagerViewProperties(alias + \"_reports\");");
    }

    @Test
    void twoViewsThatHoldEachOtherEnd() {
        JavaFileObject mentor = source("MentorView",
            "package sample.vs;",
            "import java.util.List;",
            "import org.drivine.annotation.GraphRelationship;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class MentorView {",
            "  @Root public Person person;",
            "  @GraphRelationship(type = \"MENTORS\") public List<MenteeView> mentees;",
            "}");
        JavaFileObject mentee = source("MenteeView",
            "package sample.vs;",
            "import org.drivine.annotation.GraphRelationship;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class MenteeView {",
            "  @Root public Person person;",
            "  @GraphRelationship(type = \"MENTORED_BY\") public MentorView mentor;",
            "}");

        Compilation compilation = compile(mentor, mentee);

        assertThat(compilation).succeeded();
        assertThat(compilation)
            .generatedSourceFile("sample.vs.MentorViewProperties")
            .contentsAsUtf8String()
            .contains("return new MenteeViewProperties(alias + \"_mentees\");");
        assertThat(compilation)
            .generatedSourceFile("sample.vs.MenteeViewProperties")
            .contentsAsUtf8String()
            .contains("return new MentorViewProperties(alias + \"_mentor\");");
        assertThat(compilation)
            .generatedSourceFile("sample.vs.MentorViewQueryDsl")
            .contentsAsUtf8String()
            .contains("new MenteeViewProperties(\"mentees\")");
    }

    @Test
    void aRelationshipFragmentWhoseTargetIsAViewGivesTheViewItsProperties() {
        JavaFileObject employee = source("EmployeeView",
            "package sample.vs;",
            "import org.drivine.annotation.GraphRelationship;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class EmployeeView {",
            "  @Root public Person person;",
            "  @GraphRelationship(type = \"WORKS_FOR\") public Company employer;",
            "}");
        JavaFileObject hire = source("Hire",
            "package sample.vs;",
            "import org.drivine.annotation.RelationshipFragment;",
            "@RelationshipFragment",
            "public class Hire {",
            "  public String role;",
            "  public EmployeeView target;",
            "}");
        JavaFileObject staffing = source("StaffingView",
            "package sample.vs;",
            "import java.util.List;",
            "import org.drivine.annotation.GraphRelationship;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class StaffingView {",
            "  @Root public Company company;",
            "  @GraphRelationship(type = \"HIRED\") public List<Hire> hires;",
            "}");

        Compilation compilation = compile(employee, hire, staffing);

        assertThat(compilation).succeeded();
        assertThat(compilation)
            .generatedSourceFile("sample.vs.EmployeeViewProperties")
            .contentsAsUtf8String()
            .contains("new CompanyProperties(alias + \"_employer\")");
        assertThat(compilation)
            .generatedSourceFile("sample.vs.HireProperties")
            .contentsAsUtf8String()
            .doesNotContain("target");
    }

    @Test
    void aRelationshipFragmentHeldByTwoViewsAndANestedOneHasOneProperties() {
        JavaFileObject employment = source("Employment",
            "package sample.vs;",
            "import org.drivine.annotation.RelationshipFragment;",
            "@RelationshipFragment",
            "public class Employment {",
            "  public String role;",
            "  public Company target;",
            "}");
        JavaFileObject career = source("CareerView",
            "package sample.vs;",
            "import java.util.List;",
            "import org.drivine.annotation.GraphRelationship;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class CareerView {",
            "  @Root public Person person;",
            "  @GraphRelationship(type = \"WORKED_AT\") public List<Employment> history;",
            "}");
        JavaFileObject cv = source("CvView",
            "package sample.vs;",
            "import java.util.List;",
            "import org.drivine.annotation.GraphRelationship;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class CvView {",
            "  @Root public Person person;",
            "  @GraphRelationship(type = \"WORKED_AT\") public List<Employment> jobs;",
            "  @GraphRelationship(type = \"REFERS\") public List<CareerView> referees;",
            "}");

        Compilation compilation = compile(employment, career, cv);

        // A second Properties class of the same name would fail the compile on the Filer.
        assertThat(compilation).succeeded();
        assertThat(compilation)
            .generatedSourceFile("sample.vs.CareerViewProperties")
            .contentsAsUtf8String()
            .contains("new EmploymentProperties(alias + \"_history\")");
        assertThat(compilation)
            .generatedSourceFile("sample.vs.CvViewQueryDsl")
            .contentsAsUtf8String()
            .contains("new EmploymentProperties(\"jobs\")");
    }

    @Test
    void aStaticFieldIsNoProperty() {
        JavaFileObject view = source("PersonView",
            "package sample.vs;",
            "import org.drivine.annotation.GraphView;",
            "import org.drivine.annotation.Root;",
            "@GraphView",
            "public class PersonView {",
            "  public static final String NAME = \"person\";",
            "  @Root public Person person;",
            "}");

        Compilation compilation = compile(view);

        assertThat(compilation).succeeded();
        assertThat(compilation)
            .generatedSourceFile("sample.vs.PersonProperties")
            .contentsAsUtf8String()
            .doesNotContain("KIND");
        assertThat(compilation)
            .generatedSourceFile("sample.vs.PersonViewQueryDsl")
            .contentsAsUtf8String()
            .doesNotContain("NAME");
    }
}
