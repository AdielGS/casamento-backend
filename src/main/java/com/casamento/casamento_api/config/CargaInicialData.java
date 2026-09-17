package com.casamento.casamento_api.config;

import com.casamento.casamento_api.model.Presente;
import com.casamento.casamento_api.repository.PresenteRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

@Configuration
public class CargaInicialData {

    @Bean
    CommandLineRunner popularBanco(PresenteRepository repository) {
        return args -> {
            // Se ainda não tiver nenhum presente cadastrado, insere os primeiros
            if (repository.count() == 0) {
                repository.saveAll(List.of(
                        new Presente("Café da Manhã a Dois", "Para começar bem o dia na Lua de Mel", "☕", new BigDecimal("50.00")),
                        new Presente("Jantar Romântico", "Comemoração especial dos recém-casados", "🍽️", new BigDecimal("150.00")),
                        new Presente("Almofadas pro Sofá", "Ajudar a decorar a sala da casa nova", "🛋️", new BigDecimal("80.00")),
                        new Presente("Passeio de Barco", "Um dia inesquecível na praia", "⛵", new BigDecimal("220.00"))
                ));
                System.out.println(">>> Presentes iniciais cadastrados com sucesso! <<<");
            }
        };
    }
}