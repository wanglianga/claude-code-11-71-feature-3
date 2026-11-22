package com.mpi.feeverify.support;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 用户目录与授权解析：按静态 token 识别用户，并按权限策略族解析可见机构。
 * 供排行路由、影子比较等模块复用。
 */
@Component
public class UserDirectory {

    private final JdbcTemplate jdbc;

    public UserDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> authenticate(String token) {
        if (token == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少 token");
        }
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT * FROM app_user WHERE token = ?", token);
        if (users.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "token 无效");
        }
        return Jsons.normalizeRow(users.get(0));
    }

    /**
     * 解析用户在策略族下的可见机构。
     *
     * @return null 表示省级账号（全机构可见）；否则为可见机构编码列表
     */
    @SuppressWarnings("unchecked")
    public List<String> allowedOrgs(Map<String, Object> user, String policyFamily) {
        Map<String, Object> policy = Jsons.normalizeRow(jdbc.queryForMap(
                "SELECT * FROM permission_policy WHERE family = ? AND status = 'ACTIVE'"
                        + " ORDER BY version DESC LIMIT 1", policyFamily));
        List<Map<String, Object>> rules = Jsons.asListOfMaps(policy.get("rules"));
        for (Map<String, Object> rule : rules) {
            if (String.valueOf(rule.get("role")).equals(String.valueOf(user.get("role")))) {
                Object allowed = rule.get("allowedOrgs");
                if ("*".equals(allowed)) {
                    return null;
                }
                if (allowed instanceof List<?> list) {
                    List<String> orgs = list.stream().map(String::valueOf).toList();
                    if (user.get("org_code") == null || !orgs.contains(String.valueOf(user.get("org_code")))) {
                        throw new ApiException(HttpStatus.FORBIDDEN,
                                "AUTH_DENIED: 用户机构 " + user.get("org_code") + " 不在策略 "
                                        + policyFamily + " v" + policy.get("version") + " 授权范围内");
                    }
                    return orgs;
                }
            }
        }
        throw new ApiException(HttpStatus.FORBIDDEN,
                "AUTH_DENIED: 角色 " + user.get("role") + " 在策略族 " + policyFamily + " 下无授权规则");
    }
}
