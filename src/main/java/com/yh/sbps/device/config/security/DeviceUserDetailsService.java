package com.yh.sbps.device.config.security;

import com.yh.sbps.device.entity.Role;
import com.yh.sbps.device.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DeviceUserDetailsService implements UserDetailsService {

    private final String serviceUsername = "service-user";
    @Value("${service-user.email}")
    private String serviceUserEmail;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (serviceUsername.equals(username)) {
            return new User(serviceUserEmail, "N/A", serviceUsername, Role.SERVICE_USER);
        }
        throw new UsernameNotFoundException("User not found: " + username);
    }
}