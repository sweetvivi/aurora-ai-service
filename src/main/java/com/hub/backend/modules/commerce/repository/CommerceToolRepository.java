package com.hub.backend.modules.commerce.repository;

import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface CommerceToolRepository extends JpaRepository<CommerceToolEntity, Long>,
        JpaSpecificationExecutor<CommerceToolEntity> {

    List<CommerceToolEntity> findBySiteIdAndStatusOrderBySortOrderAscIdAsc(Long siteId, String status);

    /**
     * 某个场景下、某站点启用的工具，按配置顺序。
     *
     * <p>场景（{@code scene}）必须参与过滤：电商目录与绘画工具库共用一张表，
     * 少这个条件两边会互相串。</p>
     */
    List<CommerceToolEntity> findBySiteIdAndSceneAndStatusOrderBySortOrderAscIdAsc(
            Long siteId, String scene, String status);

    /**
     * 全站启用的工具，按配置顺序。
     *
     * <p>给智能体工具清单用：那个场景只有「这个平台上有哪些工具」的概念，手上没有站点，
     * 真正执行时再按站点 + toolKey 校验一次。</p>
     */
    List<CommerceToolEntity> findByStatusOrderBySortOrderAscIdAsc(String status);

    /**
     * 全站某场景启用的工具。
     *
     * <p>智能体的电商工具清单用它，只列 {@code commerce}：绘画工具是页面内的表单，
     * 不该出现在对话工具列表里（清单每轮都随请求发一遍，多几十条纯属浪费 token）。</p>
     */
    List<CommerceToolEntity> findBySceneAndStatusOrderBySortOrderAscIdAsc(String scene, String status);

    Optional<CommerceToolEntity> findFirstBySiteIdAndToolKeyAndStatus(Long siteId,
                                                                    String toolKey,
                                                                    String status);

    /**
     * 管理端做 toolKey 唯一性检查用：不按 site_id 过滤。
     *
     * <p>不能直接写 {@code findBySiteIdAndToolKey(siteId, key)}：站点为 null（平台全局工具）时
     * JPA 会生成 {@code site_id = null}，永远匹配不到行，唯一性检查就形同虚设。这里取回同 key
     * 的全部行，再在服务层按 {@code Objects.equals} 比对站点。</p>
     */
    List<CommerceToolEntity> findByToolKey(String toolKey);
}
