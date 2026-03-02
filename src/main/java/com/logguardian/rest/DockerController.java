package com.logguardian.rest;

import com.github.dockerjava.api.model.Container;
import com.logguardian.model.LogLine;
import com.logguardian.rest.model.ContainerRulesetRequest;
import com.logguardian.service.DockerContainerService;
import jakarta.ws.rs.QueryParam;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;

@Slf4j
@RestController
@AllArgsConstructor
public class DockerController {

    private final DockerContainerService service;

    @GetMapping(value = "/api/running/containers")
    public ResponseEntity<List<Container>> retrieveRunningContainer(){
        try {
            return ResponseEntity.ok(service.getRunningContainerList());
        }catch(Exception e){
            log.info(Arrays.toString(e.getStackTrace()));
            return ResponseEntity.badRequest().body(null);
        }
    }

    @PostMapping(value = "/api/tailing/start")
    public ResponseEntity<?> startTailing(@RequestBody ContainerRulesetRequest request){
        try {
            service.startTailing(request);
            return ResponseEntity.ok(HttpStatus.OK);
        }catch (Exception e){
            log.error("error");
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping(value = "/api/tailing/stop")
    public ResponseEntity<?> stopTailing(@RequestBody ContainerRulesetRequest request){
        try {
            service.stopTrailing(request);
            return ResponseEntity.ok(HttpStatus.OK);
        }catch (Exception e){
            return ResponseEntity.badRequest().build();
        }
    }
}
