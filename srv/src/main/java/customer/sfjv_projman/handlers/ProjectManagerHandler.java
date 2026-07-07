package customer.sfjv_projman.handlers;

import com.sap.cds.Result;
import com.sap.cds.Row;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.AnalysisResult;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.cds.CdsCreateEventContext;
import com.sap.cds.services.cds.CdsDeleteEventContext;
import com.sap.cds.services.cds.CdsReadEventContext;
import com.sap.cds.services.cds.CdsUpdateEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import cds.gen.sfjv.projman.service.projectmanager.Employee;
import cds.gen.sfjv.projman.service.projectmanager.Employee_;
import cds.gen.sfjv.projman.service.projectmanager.SFSFUser_;
import cds.gen.sfjv.projman.service.projectmanager.Project_;
import cds.gen.sfjv.projman.service.projectmanager.ProjectManager_;

@Component
@ServiceName(ProjectManager_.CDS_NAME)
public class ProjectManagerHandler implements EventHandler {

    private static final Logger logger = LoggerFactory.getLogger(ProjectManagerHandler.class);
    private static final String NS = "sfjv.projman.model.db.";

    private final PersistenceService db;
    private final CqnService userService; // PLTUserManagement
    private final CqnService assService; // ECEmployeeProfile
    private final CdsModel model;

    public ProjectManagerHandler(
            PersistenceService db,
            CdsModel model,
            @Qualifier("PLTUserManagement") CqnService userService,
            @Qualifier("ECEmployeeProfile") CqnService assService) {
        this.db = db;
        this.model = model;
        this.userService = userService;
        this.assService = assService;
    }

    /*** HELPERS ***/

    // Helper cho tạo Employee nếu chưa tồn tại
    private void executeCreateEmployee(String userId) {
        Result existing = db.run(Select.from(NS + "Employee")
                .columns("userId")
                .where(e -> e.get("userId").eq(userId)));
        if (existing.first().isEmpty()) {
            Result sfsfUser = userService.run(Select.from("User")
                    .columns("userId", "username", "defaultFullName", "email", "division", "department", "title")
                    .where(u -> u.get("userId").eq(userId)));
            Optional<Row> row = sfsfUser.first();
            if (row.isPresent()) {
                db.run(Insert.into(NS + "Employee").entry(row.get()));
            }
        }
    }

    // Helper cho update Employee khi member thay đổi
    private void executeUpdateEmployee(String entity, String entityId, String userId) {
        Result item = db.run(Select.from(NS + entity)
                .columns("member_userId")
                .where(e -> e.get("ID").eq(entityId)));
        Optional<Row> row = item.first();
        if (row.isPresent()) {
            Object currentUserId = row.get().get("member_userId");
            if (currentUserId == null || !currentUserId.equals(userId)) {
                executeCreateEmployee(userId);
                createAssignment(entity, entityId, userId);
            }
        }
    }

    // Helper tạo assignment bên SFSF
    private void createAssignment(String entity, String entityId, String userId) {
        Result item = db.run(Select.from(NS + entity)
                .columns(
                        b -> b.to("parent").get("name").as("name"),
                        b -> b.to("parent").get("description").as("description"),
                        b -> b.to("parent").get("startDate").as("startDate"),
                        b -> b.to("parent").get("endDate").as("endDate"),
                        b -> b.to("role").get("name").as("role"))
                .where(e -> e.get("ID").eq(entityId)));

        Optional<Row> row = item.first();
        if (row.isPresent()) {
            Row r = row.get();
            Map<String, Object> assignment = new HashMap<>();
            assignment.put("userId", userId);
            assignment.put("project", r.get("name"));
            assignment.put("description", r.get("role") + " of " + r.get("description"));
            assignment.put("startDate", r.get("startDate"));
            assignment.put("endDate", r.get("endDate"));

            logger.info("Creating assignment: {}", assignment);

            Result inserted = assService.run(Insert.into("Background_SpecialAssign").entry(assignment));
            if (!inserted.list().isEmpty()) {
                db.run(Update.entity(NS + entity)
                        .data("hasAssignment", true)
                        .where(e -> e.get("ID").eq(entityId)));
            }
        }
    }

    // Helper xóa cascade
    private void deepDelete(String entityId, String childEntity) {
        db.run(Delete.from(NS + childEntity).where(c -> c.get("parent_ID").eq(entityId)));
    }

    /*** HANDLERS ***/

    // READ SFSF_User -> forward tới remote service
    @On(event = CqnService.EVENT_READ, entity = SFSFUser_.CDS_NAME)
    public void readSfsfUser(CdsReadEventContext context) {
        // Bỏ cột không sortable khỏi orderBy trước khi forward
        logger.info("356read request to PLTUserManagement");
        CqnSelect query = context.getCqn();
        // Nếu cần loại bỏ orderBy theo defaultFullName, xử lý ở tầng query builder tùy
        // CAP Java version.
        Result result = userService.run(query);
        context.setResult(result);
    }

    // BEFORE CREATE Member: đảm bảo Employee tồn tại
    @Before(event = CqnService.EVENT_CREATE, entity = "ProjectManager.Member")
    public void createEmployee(CdsCreateEventContext context) {
        Map<String, Object> data = context.getCqn().entries().get(0);
        Object userId = data.get("member_userId");
        if (userId != null) {
            executeCreateEmployee(userId.toString());
        }
    }

    // AFTER CREATE Member: tạo assignment bên SFSF
    @After(event = CqnService.EVENT_CREATE, entity = "ProjectManager.Member")
    public void afterCreateMember(CdsCreateEventContext context, Result result) {
        Optional<Row> row = result.first();
        if (row.isPresent()) {
            Row r = row.get();
            createAssignment("Member", r.get("ID").toString(), r.get("member_userId").toString());
        }
    }

    // BEFORE UPDATE Member: kiểm tra nếu member thay đổi
    @Before(event = CqnService.EVENT_UPDATE, entity = "ProjectManager.Member")
    public void updateEmployee(CdsUpdateEventContext context) {
        Map<String, Object> data = context.getCqn().entries().get(0);
        Object newUserId = data.get("member_userId");
        if (newUserId != null) {
            AnalysisResult result = CqnAnalyzer.create(model).analyze(context.getCqn().ref());
            String id = result.targetKeys().get("ID").toString();
            executeUpdateEmployee("Member", id, newUserId.toString());
        }
    }

    // BEFORE DELETE Project/Member: cascade xóa Activity/Member
    @Before(event = CqnService.EVENT_DELETE, entity = { "ProjectManager.Project", "ProjectManager.Member" })
    public void deleteChildren(CdsDeleteEventContext context) {
        String entity = context.getTarget().getName(); // vd: "ProjectManager.Project"
        AnalysisResult result = CqnAnalyzer.create(model).analyze(context.getCqn().ref());
        String id = result.targetKeys().get("ID").toString();

        if (entity.contains("Project")) {
            deepDelete(id, "Activity");
            deepDelete(id, "Member");
        } else {
            Result item = db.run(Select.from(NS + "Member")
                    .columns("parent_ID")
                    .where(e -> e.get("ID").eq(id)));
            item.first().ifPresent(row -> {
                Object parentId = row.get("parent_ID");
                if (parentId != null) {
                    deepDelete(parentId.toString(), "Activity");
                }
            });
        }
    }

    // AFTER UPDATE/DELETE Member, AFTER DELETE Project: dọn Employee không còn
    // assignment
    @After(event = { CqnService.EVENT_UPDATE, CqnService.EVENT_DELETE }, entity = { "ProjectManager.Member",
            "ProjectManager.Project" })
    public void deleteUnassignedEmployees() {
        // Lấy danh sách userId đang được assign
        Result membersResult = db.run(Select.from(NS + "Member").columns("member_userId"));
        java.util.Set<Object> assignedUserIds = membersResult.list().stream()
                .map(row -> row.get("member_userId"))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        // Lấy toàn bộ Employee, lọc bằng Java xem ai không nằm trong assignedUserIds
        Result allEmployees = db.run(Select.from(NS + "Employee").columns("userId"));
        List<Row> toDelete = allEmployees.list().stream()
                .filter(row -> !assignedUserIds.contains(row.get("userId")))
                .collect(java.util.stream.Collectors.toList());

        // Xóa từng Employee unassigned
        for (Row row : toDelete) {
            db.run(Delete.from(NS + "Employee").where(e -> e.get("userId").eq(row.get("userId"))));
        }
    }

    // BEFORE SAVE Project (Fiori Draft support)
    @SuppressWarnings("unchecked")
    @Before(event = "SAVE", entity = "ProjectManager.Project")
    public void beforeSaveProject(EventContext context) {
        // Lấy team member từ payload draft
        List<Map<String, Object>> team = (List<Map<String, Object>>) context.get("team");
        if (team == null)
            return;

        String projectId = context.get("ID") != null ? context.get("ID").toString() : null;
        if (projectId == null)
            return;

        List<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> member : team) {
            Map<String, Object> u = new HashMap<>();
            u.put("ID", member.get("ID"));
            u.put("member_userId", member.get("member_userId"));
            users.add(u);
        }

        Result membersResult = db.run(Select.from(NS + "Member")
                .columns("ID", "member_userId")
                .where(e -> e.get("parent_ID").eq(projectId)));
        List<Row> members = membersResult.list();

        // Members bị xóa
        for (Row member : members) {
            boolean stillExists = users.stream()
                    .anyMatch(u -> u.get("ID").equals(member.get("ID")));
            if (!stillExists) {
                db.run(Delete.from(NS + "Activity").where(a -> a.get("assignedTo_ID").eq(member.get("ID"))));
            }
        }

        // Members mới thêm
        for (Map<String, Object> user : users) {
            boolean isNew = members.stream()
                    .noneMatch(m -> m.get("ID").equals(user.get("ID")));
            if (isNew) {
                executeCreateEmployee(user.get("member_userId").toString());
            }
        }

        // Members được cập nhật
        for (Map<String, Object> user : users) {
            boolean isUpdated = members.stream()
                    .anyMatch(m -> m.get("ID").equals(user.get("ID")));
            if (isUpdated) {
                executeUpdateEmployee("Member", user.get("ID").toString(), user.get("member_userId").toString());
            }
        }
    }

    // AFTER SAVE Project (Fiori Draft support)
    // @After(event = "SAVE", entity = "ProjectManager.Project")
    // public void afterSaveProject(Result result) {
    //     Optional<Row> row = result.first();
    //     if (row.isEmpty())
    //         return;
    //     Row data = row.get();
    //     String projectId = data.get("ID").toString();

    //     Result unassigned = db.run(Select.from(NS + "Member")
    //             .columns("ID", "member_userId")
    //             .where(e -> e.get("parent_ID").eq(projectId).and(e.get("hasAssignment").eq(false))));

    //     for (Row m : unassigned.list()) {
    //         createAssignment("Member", m.get("ID").toString(), m.get("member_userId").toString());
    //     }

    //     deleteUnassignedEmployees();
    // }
}