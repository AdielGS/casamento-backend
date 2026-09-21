package com.casamento.casamento_api.controller;

import com.casamento.casamento_api.model.Mensagem;
import com.casamento.casamento_api.repository.MensagemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/mensagens")
@CrossOrigin(origins = "*")
public class MensagemController {

    private final MensagemRepository repository;

    public MensagemController(MensagemRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Mensagem> listarMensagens() {
        return repository.findAllByOrderByDataEnvioDesc();
    }

    @PostMapping
    public ResponseEntity<Mensagem> salvarMensagem(@RequestBody Mensagem mensagem) {
        if (mensagem.getAutor() == null || mensagem.getAutor().isBlank() ||
                mensagem.getTexto() == null || mensagem.getTexto().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Mensagem salva = repository.save(mensagem);
        return ResponseEntity.status(HttpStatus.CREATED).body(salva);
    }
}