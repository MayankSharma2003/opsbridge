package io.opsbridge.support.notification;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Dedicated pool so a slow/hung mail server can't starve other async work off the shared default executor.
// DiscardPolicy over CallerRunsPolicy is the key choice -- CallerRunsPolicy would hand a rejected
// send back to the request thread, exactly the blocking this exists to prevent. A dropped courtesy
// email beats a stalled incident declaration.
@Configuration
@EnableAsync
public class MailAsyncConfig {

    public static final String MAIL_EXECUTOR = "mailExecutor";

    @Bean(MAIL_EXECUTOR)
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        // Don't hold shutdown open on a hung SMTP socket.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
