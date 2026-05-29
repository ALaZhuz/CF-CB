package org.example.repository;

import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * sqlite数据库操作
 */
@Repository
public class ReviewNotifyRepository {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final Connection connection;

    public ReviewNotifyRepository(Connection reviewNotifyConnection) {
        this.connection = reviewNotifyConnection;
    }

    public boolean exists(long reviewId) {
        try (PreparedStatement ps = connection.prepareStatement("select 1 from review_notify where review_id = ?")) {
            ps.setLong(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public ReviewRecord findById(long reviewId) {
        try (PreparedStatement ps = connection.prepareStatement("select * from review_notify where review_id = ?")) {
            ps.setLong(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void upsert(ReviewRecord record) {
        String sql = """
                insert into review_notify(review_id, review_name, deadline, status, submitter_id, submitter_name, moderator_ids, reviewer_ids, viewer_ids, new_notified, close_notified, cancel_notified, near_expired_last_sent, overdue_last_sent, overdue_manager_last_sent, pending_delete, created_at, updated_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(review_id) do update set
                  review_name=excluded.review_name,
                  deadline=excluded.deadline,
                  status=excluded.status,
                  submitter_id=excluded.submitter_id,
                  submitter_name=excluded.submitter_name,
                  moderator_ids=excluded.moderator_ids,
                  reviewer_ids=excluded.reviewer_ids,
                  viewer_ids=excluded.viewer_ids,
                  new_notified=excluded.new_notified,
                  close_notified=excluded.close_notified,
                  cancel_notified=excluded.cancel_notified,
                  near_expired_last_sent=excluded.near_expired_last_sent,
                  overdue_last_sent=excluded.overdue_last_sent,
                  overdue_manager_last_sent=excluded.overdue_manager_last_sent,
                  pending_delete=excluded.pending_delete,
                  updated_at=excluded.updated_at
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, record);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void updateStatus(long reviewId, String status) {
        try (PreparedStatement ps = connection.prepareStatement("update review_notify set status = ?, updated_at = ? where review_id = ?")) {
            ps.setString(1, status);
            ps.setString(2, FMT.format(LocalDateTime.now()));
            ps.setLong(3, reviewId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void updateNewNotified(long reviewId, boolean notified) {
        updateBooleanField(reviewId, "new_notified", notified);
    }

    public void updateCloseNotified(long reviewId, boolean notified) {
        updateBooleanField(reviewId, "close_notified", notified);
    }

    public void updateCancelNotified(long reviewId, boolean notified) {
        updateBooleanField(reviewId, "cancel_notified", notified);
    }

    public void updateLastSent(long reviewId, String field, LocalDateTime time) {
        try (PreparedStatement ps = connection.prepareStatement("update review_notify set " + field + " = ?, updated_at = ? where review_id = ?")) {
            ps.setString(1, FMT.format(time));
            ps.setString(2, FMT.format(LocalDateTime.now()));
            ps.setLong(3, reviewId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        try (PreparedStatement ps = connection.prepareStatement("delete from review_notify where review_id in (" + placeholders + ")")) {
            int idx = 1;
            for (Long id : ids) ps.setLong(idx++, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<ReviewRecord> findAll() {
        List<ReviewRecord> list = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("select * from review_notify")) {
            while (rs.next()) list.add(map(rs));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return list;
    }

    public List<ReviewRecord> findOpenRecords() {
        List<ReviewRecord> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("select * from review_notify where status = 'OPEN'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return list;
    }

    public void markPendingDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        try (PreparedStatement ps = connection.prepareStatement("update review_notify set pending_delete = 1, updated_at = ? where review_id in (" + placeholders + ")")) {
            ps.setString(1, FMT.format(LocalDateTime.now()));
            int idx = 2;
            for (Long id : ids) ps.setLong(idx++, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void updateBooleanField(long reviewId, String field, boolean value) {
        try (PreparedStatement ps = connection.prepareStatement("update review_notify set " + field + " = ?, updated_at = ? where review_id = ?")) {
            ps.setInt(1, value ? 1 : 0);
            ps.setString(2, FMT.format(LocalDateTime.now()));
            ps.setLong(3, reviewId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void bind(PreparedStatement ps, ReviewRecord r) throws Exception {
        ps.setLong(1, r.reviewId);
        ps.setString(2, r.reviewName);
        ps.setString(3, r.deadline);
        ps.setString(4, r.status);
        ps.setString(5, r.submitterId);
        ps.setString(6, r.submitterName);
        ps.setString(7, String.join(",", r.moderatorIds));
        ps.setString(8, String.join(",", r.reviewerIds));
        ps.setString(9, String.join(",", r.viewerIds));
        ps.setInt(10, r.newNotified ? 1 : 0);
        ps.setInt(11, r.closeNotified ? 1 : 0);
        ps.setInt(12, r.cancelNotified ? 1 : 0);
        ps.setString(13, r.nearExpiredLastSent);
        ps.setString(14, r.overdueLastSent);
        ps.setString(15, r.overdueManagerLastSent);
        ps.setInt(16, r.pendingDelete ? 1 : 0);
        ps.setString(17, r.createdAt);
        ps.setString(18, r.updatedAt);
    }

    private ReviewRecord map(ResultSet rs) throws Exception {
        ReviewRecord r = new ReviewRecord();
        r.reviewId = rs.getLong("review_id");
        r.reviewName = rs.getString("review_name");
        r.deadline = rs.getString("deadline");
        r.status = rs.getString("status");
        r.submitterId = rs.getString("submitter_id");
        r.submitterName = rs.getString("submitter_name");
        r.moderatorIds = split(rs.getString("moderator_ids"));
        r.reviewerIds = split(rs.getString("reviewer_ids"));
        r.viewerIds = split(rs.getString("viewer_ids"));
        r.newNotified = rs.getInt("new_notified") == 1;
        r.closeNotified = rs.getInt("close_notified") == 1;
        r.cancelNotified = rs.getInt("cancel_notified") == 1;
        r.nearExpiredLastSent = rs.getString("near_expired_last_sent");
        r.overdueLastSent = rs.getString("overdue_last_sent");
        r.overdueManagerLastSent = rs.getString("overdue_manager_last_sent");
        r.pendingDelete = rs.getInt("pending_delete") == 1;
        r.createdAt = rs.getString("created_at");
        r.updatedAt = rs.getString("updated_at");
        return r;
    }

    private List<String> split(String v) {
        if (v == null || v.isBlank()) return new ArrayList<>();
        String[] arr = v.split(",");
        List<String> out = new ArrayList<>();
        for (String s : arr) if (!s.isBlank()) out.add(s);
        return out;
    }

    public static class ReviewRecord {
        public long reviewId;
        public String reviewName;
        public String deadline;
        public String status;
        public String submitterId;
        public String submitterName;
        public List<String> moderatorIds = new ArrayList<>();
        public List<String> reviewerIds = new ArrayList<>();
        public List<String> viewerIds = new ArrayList<>();
        public boolean newNotified;
        public boolean closeNotified;
        public boolean cancelNotified;
        public String nearExpiredLastSent;
        public String overdueLastSent;
        public String overdueManagerLastSent;
        public boolean pendingDelete;
        public String createdAt;
        public String updatedAt;
    }
}
