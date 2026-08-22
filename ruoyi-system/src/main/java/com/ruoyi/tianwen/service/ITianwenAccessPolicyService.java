package com.ruoyi.tianwen.service;

import com.ruoyi.tianwen.domain.*;
import java.util.Map;

public interface ITianwenAccessPolicyService {
  TianwenAccessResult check(
      TianwenH5User user, Map<String, Object> recommendation, boolean manuallyUnlocked);
}
