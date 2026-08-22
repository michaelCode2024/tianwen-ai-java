package com.ruoyi.tianwen.mapper;
import com.ruoyi.tianwen.domain.TianwenRecommendOverride;
import java.util.List;
public interface TianwenRecommendOverrideMapper {
    TianwenRecommendOverride selectById(String recommendationId);
    List<TianwenRecommendOverride> selectByIds(List<String> ids);
    int upsert(TianwenRecommendOverride entity);
    int deleteById(String recommendationId);
}
