package com.vn.autoconfigure.agent;

import com.vn.agent.AIConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {
}

