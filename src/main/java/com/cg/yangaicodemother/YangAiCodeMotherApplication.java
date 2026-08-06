package com.cg.yangaicodemother;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan(value = "com.cg.yangaicodemother.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
public class YangAiCodeMotherApplication {

	public static void main(String[] args) {
		SpringApplication.run(YangAiCodeMotherApplication.class, args);
	}

}
