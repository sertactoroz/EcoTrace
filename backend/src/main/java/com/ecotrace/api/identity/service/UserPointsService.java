package com.ecotrace.api.identity.service;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.identity.api.UserDirectory;
import com.ecotrace.api.identity.api.UserPointsFacade;
import com.ecotrace.api.identity.api.UserPointsSnapshot;
import com.ecotrace.api.identity.api.UserSummary;
import com.ecotrace.api.identity.entity.User;
import com.ecotrace.api.identity.repository.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPointsService implements UserPointsFacade, UserDirectory {

    private final UserRepository users;

    public UserPointsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public long getTotalPoints(UUID userId) {
        return users.findById(userId)
                .map(User::getTotalPoints)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found: " + userId));
    }

    @Override
    @Transactional
    public void setPointsAndLevel(UUID userId, long totalPoints, int level) {
        User u = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found: " + userId));
        u.setTotalPoints(totalPoints);
        u.setLevel(level);
        users.save(u);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserPointsSnapshot> findAllWithPoints() {
        return users.findAllByTotalPointsGreaterThan(0L).stream()
                .map(u -> new UserPointsSnapshot(u.getId(), u.getTotalPoints()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> getSummaries(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        return users.findAllByIdIn(userIds).stream()
                .map(u -> new UserSummary(u.getId(), u.getDisplayName(), u.getAvatarUrl(), u.getLevel()))
                .collect(Collectors.toMap(UserSummary::userId, Function.identity()));
    }
}
