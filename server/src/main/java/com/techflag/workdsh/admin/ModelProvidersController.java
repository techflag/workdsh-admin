package com.techflag.workdsh.admin;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
@RestController
public class ModelProvidersController {
 private final ModelProviders providers;private final ModelGateway gateway;private final AuthService auth;
 public ModelProvidersController(ModelProviders providers,ModelGateway gateway,AuthService auth){this.providers=providers;this.gateway=gateway;this.auth=auth;}
 @GetMapping("/api/admin/model-providers") public ResponseEntity<ModelProviders.View> read(@RequestHeader("Authorization")String bearer){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(providers.read(auth.actor(bearer)));}
 @PostMapping("/api/admin/model-providers/{id}/reveal-key") public ResponseEntity<java.util.Map<String,String>> reveal(@RequestHeader("Authorization")String bearer,@PathVariable String id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("key",providers.reveal(auth.actor(bearer),id)));}
 @PutMapping("/api/admin/model-providers") @Transactional public ResponseEntity<ModelProviders.View> update(@RequestHeader("Authorization")String bearer,@RequestBody ModelProviders.Update update){var view=providers.update(auth.actor(bearer),update);gateway.update(bearer,new ModelGateway.Update(update.accessKey()));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);}
}
