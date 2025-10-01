package com.yh.sbps.device.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yh.sbps.device.service.ShellyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private final ShellyService shellyService;

    public DeviceController(ShellyService shellyService) {
        this.shellyService = shellyService;
    }

    @PostMapping("/{plugId}/toggle")
    public String togglePlug(@PathVariable String plugId, @RequestParam boolean on) {
        shellyService.sendCommand(plugId, on);
        return "Plug [" + plugId + "] toggled " + (on ? "ON" : "OFF");
    }

    @GetMapping("/{plugId}/status")
    public ResponseEntity<JsonNode> getStatus(@PathVariable String plugId) {
        JsonNode status = shellyService.getLastStatus(plugId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }
}