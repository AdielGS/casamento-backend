package com.casamento.casamento_api.repository;

import com.casamento.casamento_api.model.Mensagem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MensagemRepository extends JpaRepository<Mensagem, Long> {
    List<Mensagem> findAllByOrderByDataEnvioDesc();
}