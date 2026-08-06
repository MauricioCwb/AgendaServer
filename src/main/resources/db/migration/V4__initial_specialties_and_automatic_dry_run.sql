-- Catálogo inicial do AgendaJá baseado nas subclasses CNAE 2.1 da CONCLA/IBGE.
-- A relação é uma curadoria operacional inicial e pode ser administrada pelo AgendaWeb.

INSERT INTO agenda_specialties(name, slug, description, active) VALUES
('Eletricista', 'eletricista', 'Instalação, manutenção e reparo de instalações elétricas.', TRUE),
('Encanador e instalações hidráulicas', 'encanador-hidraulica', 'Instalações hidráulicas, sanitárias e de gás.', TRUE),
('Ar-condicionado e refrigeração', 'ar-condicionado-refrigeracao', 'Instalação, manutenção e reparo de sistemas de climatização e refrigeração.', TRUE),
('Pintura residencial e predial', 'pintura-residencial-predial', 'Pintura interna e externa de imóveis e edifícios.', TRUE),
('Gesso e drywall', 'gesso-drywall', 'Acabamentos em gesso, estuque, forros e divisórias.', TRUE),
('Portas, janelas e armários', 'portas-janelas-armarios', 'Instalação de portas, janelas, tetos, divisórias e armários embutidos.', TRUE),
('Impermeabilização', 'impermeabilizacao', 'Impermeabilização de lajes, paredes, telhados e outras estruturas.', TRUE),
('Pisos e revestimentos', 'pisos-revestimentos', 'Aplicação de pisos, revestimentos, resinas e acabamentos.', TRUE),
('Pedreiro e alvenaria', 'pedreiro-alvenaria', 'Construção, reparo e manutenção de alvenaria.', TRUE),
('Reformas e acabamentos', 'reformas-acabamentos', 'Reformas, reparos e outros serviços de acabamento da construção.', TRUE),
('Limpeza residencial e comercial', 'limpeza-residencial-comercial', 'Limpeza em residências, condomínios, escritórios e estabelecimentos.', TRUE),
('Dedetização e controle de pragas', 'dedetizacao-controle-pragas', 'Imunização e controle de pragas urbanas.', TRUE),
('Jardinagem e paisagismo', 'jardinagem-paisagismo', 'Implantação e manutenção de jardins e áreas verdes.', TRUE),
('Desentupimento e serviços de esgoto', 'desentupimento-esgoto', 'Limpeza, desobstrução e serviços relacionados a esgoto.', TRUE),
('Coleta de entulho e resíduos', 'coleta-entulho-residuos', 'Coleta e remoção de resíduos não perigosos e entulho.', TRUE),
('Chaveiro', 'chaveiro', 'Confecção de chaves, abertura e reparo de fechaduras.', TRUE),
('Marcenaria e reparo de móveis', 'marcenaria-reparo-moveis', 'Reparação, restauração e manutenção de móveis.', TRUE),
('Conserto de eletrodomésticos', 'conserto-eletrodomesticos', 'Reparação e manutenção de equipamentos eletroeletrônicos domésticos.', TRUE),
('Computadores e informática', 'computadores-informatica', 'Reparação e manutenção de computadores e periféricos.', TRUE),
('Celulares e equipamentos de comunicação', 'celulares-comunicacao', 'Reparação e manutenção de celulares e equipamentos de comunicação.', TRUE),
('Reparo de bicicletas', 'reparo-bicicletas', 'Reparação e manutenção de bicicletas e veículos não motorizados.', TRUE),
('Mecânica automotiva', 'mecanica-automotiva', 'Manutenção e reparação mecânica de veículos automotores.', TRUE),
('Funilaria e pintura automotiva', 'funilaria-pintura-automotiva', 'Lanternagem, funilaria e pintura de veículos.', TRUE),
('Elétrica automotiva', 'eletrica-automotiva', 'Manutenção e reparação elétrica de veículos.', TRUE),
('Lavagem e polimento automotivo', 'lavagem-polimento-automotivo', 'Lavagem, lubrificação e polimento de veículos.', TRUE),
('Borracharia', 'borracharia', 'Reparo e manutenção de pneus de veículos.', TRUE),
('Manutenção de motocicletas', 'manutencao-motocicletas', 'Manutenção e reparação de motocicletas e motonetas.', TRUE),
('Fotografia', 'fotografia', 'Produção de fotografias para pessoas, produtos, imóveis e eventos.', TRUE),
('Filmagem de eventos', 'filmagem-eventos', 'Filmagem de festas, cerimônias e eventos.', TRUE),
('Organização de eventos', 'organizacao-eventos', 'Organização de feiras, congressos, exposições, festas e eventos.', TRUE),
('Buffet e alimentação para eventos', 'buffet-eventos', 'Serviços de alimentação para eventos e recepções.', TRUE),
('Entrega rápida', 'entrega-rapida', 'Coleta e entrega rápida de documentos, volumes e mercadorias.', TRUE),
('Frete e mudança', 'frete-mudanca', 'Transporte rodoviário municipal e intermunicipal de cargas e mudanças.', TRUE),
('Cabeleireiro e barbearia', 'cabeleireiro-barbearia', 'Serviços de cabeleireiro, barbearia e cuidados capilares.', TRUE),
('Manicure, pedicure e estética', 'manicure-estetica', 'Manicure, pedicure, estética e cuidados com a beleza.', TRUE),
('Banho e tosa', 'banho-tosa', 'Higiene e embelezamento de animais domésticos.', TRUE),
('Hospedagem de animais', 'hospedagem-animais', 'Alojamento e hospedagem de animais domésticos.', TRUE),
('Serviços veterinários', 'servicos-veterinarios', 'Atendimento e serviços veterinários.', TRUE),
('Aulas de idiomas', 'aulas-idiomas', 'Ensino particular e cursos de idiomas.', TRUE),
('Aulas de informática', 'aulas-informatica', 'Treinamento e aulas de informática.', TRUE),
('Aulas de música', 'aulas-musica', 'Ensino e aulas de música.', TRUE),
('Contabilidade', 'contabilidade', 'Serviços contábeis, fiscais e de escrituração.', TRUE),
('Arquitetura', 'arquitetura', 'Serviços de arquitetura e planejamento de espaços.', TRUE),
('Engenharia', 'engenharia', 'Serviços técnicos de engenharia.', TRUE),
('Design de interiores', 'design-interiores', 'Planejamento e design de ambientes internos.', TRUE),
('Segurança eletrônica', 'seguranca-eletronica', 'Instalação e monitoramento de sistemas eletrônicos de segurança.', TRUE),
('Elevadores e plataformas', 'elevadores-plataformas', 'Instalação, manutenção e reparação de elevadores e plataformas.', TRUE),
('Isolamento térmico e acústico', 'isolamento-termico-acustico', 'Tratamentos térmicos, acústicos e de vibração.', TRUE),
('Poços de água', 'pocos-agua', 'Perfuração e construção de poços de água.', TRUE)
ON CONFLICT (slug) DO UPDATE SET
    name=EXCLUDED.name,
    description=EXCLUDED.description,
    active=TRUE,
    updated_at=CURRENT_TIMESTAMP;

-- Mantém a especialidade histórica e associa CNAEs amplos para que tarefas antigas possam ser processadas.
UPDATE agenda_specialties
SET description='Serviços diversos de manutenção, reparo, reforma e acabamento.', active=TRUE, updated_at=CURRENT_TIMESTAMP
WHERE slug='servicos-gerais';

WITH mappings(slug,cnae_code,description,match_primary,match_secondary) AS (VALUES
('servicos-gerais','4330499','Outras obras de acabamento da construção',TRUE,TRUE),
('servicos-gerais','4399199','Serviços especializados para construção não especificados anteriormente',TRUE,TRUE),
('eletricista','4321500','Instalação e manutenção elétrica',TRUE,TRUE),
('encanador-hidraulica','4322301','Instalações hidráulicas, sanitárias e de gás',TRUE,TRUE),
('ar-condicionado-refrigeracao','4322302','Instalação e manutenção de sistemas centrais de ar condicionado, ventilação e refrigeração',TRUE,TRUE),
('ar-condicionado-refrigeracao','3314707','Manutenção e reparação de máquinas e aparelhos de refrigeração e ventilação para uso industrial e comercial',TRUE,TRUE),
('pintura-residencial-predial','4330404','Serviços de pintura de edifícios em geral',TRUE,TRUE),
('gesso-drywall','4330403','Obras de acabamento em gesso e estuque',TRUE,TRUE),
('gesso-drywall','4330402','Instalação de portas, janelas, tetos, divisórias e armários embutidos',TRUE,TRUE),
('portas-janelas-armarios','4330402','Instalação de portas, janelas, tetos, divisórias e armários embutidos',TRUE,TRUE),
('impermeabilizacao','4330401','Impermeabilização em obras de engenharia civil',TRUE,TRUE),
('pisos-revestimentos','4330405','Aplicação de revestimentos e de resinas em interiores e exteriores',TRUE,TRUE),
('pedreiro-alvenaria','4399103','Obras de alvenaria',TRUE,TRUE),
('reformas-acabamentos','4330499','Outras obras de acabamento da construção',TRUE,TRUE),
('reformas-acabamentos','4399199','Serviços especializados para construção não especificados anteriormente',TRUE,TRUE),
('limpeza-residencial-comercial','8121400','Limpeza em prédios e em domicílios',TRUE,TRUE),
('limpeza-residencial-comercial','8129000','Atividades de limpeza não especificadas anteriormente',TRUE,TRUE),
('dedetizacao-controle-pragas','8122200','Imunização e controle de pragas urbanas',TRUE,TRUE),
('jardinagem-paisagismo','8130300','Atividades paisagísticas',TRUE,TRUE),
('desentupimento-esgoto','3702900','Atividades relacionadas a esgoto, exceto a gestão de redes',TRUE,TRUE),
('coleta-entulho-residuos','3811400','Coleta de resíduos não perigosos',TRUE,TRUE),
('chaveiro','9529102','Chaveiros',TRUE,TRUE),
('marcenaria-reparo-moveis','9529105','Reparação de artigos do mobiliário',TRUE,TRUE),
('conserto-eletrodomesticos','9521500','Reparação e manutenção de equipamentos eletroeletrônicos de uso pessoal e doméstico',TRUE,TRUE),
('computadores-informatica','9511800','Reparação e manutenção de computadores e de equipamentos periféricos',TRUE,TRUE),
('celulares-comunicacao','9512600','Reparação e manutenção de equipamentos de comunicação',TRUE,TRUE),
('reparo-bicicletas','9529104','Reparação de bicicletas, triciclos e outros veículos não motorizados',TRUE,TRUE),
('mecanica-automotiva','4520001','Serviços de manutenção e reparação mecânica de veículos automotores',TRUE,TRUE),
('funilaria-pintura-automotiva','4520002','Serviços de lanternagem ou funilaria e pintura de veículos automotores',TRUE,TRUE),
('eletrica-automotiva','4520003','Serviços de manutenção e reparação elétrica de veículos automotores',TRUE,TRUE),
('lavagem-polimento-automotivo','4520005','Serviços de lavagem, lubrificação e polimento de veículos automotores',TRUE,TRUE),
('borracharia','4520006','Serviços de borracharia para veículos automotores',TRUE,TRUE),
('manutencao-motocicletas','4543900','Manutenção e reparação de motocicletas e motonetas',TRUE,TRUE),
('fotografia','7420001','Atividades de produção de fotografias, exceto aérea e submarina',TRUE,TRUE),
('filmagem-eventos','7420004','Filmagem de festas e eventos',TRUE,TRUE),
('organizacao-eventos','8230001','Serviços de organização de feiras, congressos, exposições e festas',TRUE,TRUE),
('buffet-eventos','5620102','Serviços de alimentação para eventos e recepções - bufê',TRUE,TRUE),
('entrega-rapida','5320202','Serviços de entrega rápida',TRUE,TRUE),
('frete-mudanca','4930201','Transporte rodoviário de carga, exceto produtos perigosos e mudanças, municipal',TRUE,TRUE),
('frete-mudanca','4930202','Transporte rodoviário de carga, exceto produtos perigosos e mudanças, intermunicipal, interestadual e internacional',TRUE,TRUE),
('cabeleireiro-barbearia','9602501','Cabeleireiros, manicure e pedicure',TRUE,TRUE),
('manicure-estetica','9602501','Cabeleireiros, manicure e pedicure',TRUE,TRUE),
('manicure-estetica','9602502','Atividades de estética e outros serviços de cuidados com a beleza',TRUE,TRUE),
('banho-tosa','9609208','Higiene e embelezamento de animais domésticos',TRUE,TRUE),
('hospedagem-animais','9609207','Alojamento de animais domésticos',TRUE,TRUE),
('servicos-veterinarios','7500100','Atividades veterinárias',TRUE,TRUE),
('aulas-idiomas','8593700','Ensino de idiomas',TRUE,TRUE),
('aulas-informatica','8599603','Treinamento em informática',TRUE,TRUE),
('aulas-musica','8592903','Ensino de música',TRUE,TRUE),
('contabilidade','6920601','Atividades de contabilidade',TRUE,TRUE),
('arquitetura','7111100','Serviços de arquitetura',TRUE,TRUE),
('engenharia','7112000','Serviços de engenharia',TRUE,TRUE),
('design-interiores','7410202','Design de interiores',TRUE,TRUE),
('seguranca-eletronica','8020001','Atividades de monitoramento de sistemas de segurança eletrônico',TRUE,TRUE),
('elevadores-plataformas','4329103','Instalação, manutenção e reparação de elevadores, escadas e esteiras rolantes',TRUE,TRUE),
('isolamento-termico-acustico','4329105','Tratamentos térmicos, acústicos ou de vibração',TRUE,TRUE),
('pocos-agua','4399105','Perfuração e construção de poços de água',TRUE,TRUE)
)
INSERT INTO agenda_specialty_cnaes(specialty_id,cnae_code,description,match_primary,match_secondary,active)
SELECT s.id,m.cnae_code,m.description,m.match_primary,m.match_secondary,TRUE
FROM mappings m
JOIN agenda_specialties s ON s.slug=m.slug
ON CONFLICT(specialty_id,cnae_code) DO UPDATE SET
    description=EXCLUDED.description,
    match_primary=EXCLUDED.match_primary,
    match_secondary=EXCLUDED.match_secondary,
    active=TRUE,
    updated_at=CURRENT_TIMESTAMP;

-- O processamento passa a iniciar automaticamente após a publicação da demanda.
-- O envio continua bloqueado: jobs e convites permanecem em modo de simulação.
INSERT INTO agenda_prospecting_settings(setting_key,setting_value)
VALUES ('trigger.mode','AUTO_IMMEDIATE')
ON CONFLICT(setting_key) DO UPDATE SET setting_value='AUTO_IMMEDIATE',updated_at=CURRENT_TIMESTAMP;

UPDATE agenda_prospecting_jobs
SET dry_run=TRUE,
    send_authorized=FALSE,
    authorized_by=NULL,
    authorized_at=NULL,
    manual_trigger=FALSE,
    state=CASE WHEN state IN ('READY','SENDING') THEN 'PENDING' ELSE state END,
    not_before=CASE WHEN state='PENDING' THEN CURRENT_TIMESTAMP ELSE not_before END,
    locked_at=NULL,
    lock_owner='',
    updated_at=CURRENT_TIMESTAMP
WHERE state IN ('PENDING','READY','SENDING');

UPDATE agenda_external_invitations
SET status='DRY_RUN',sending_started_at=NULL,updated_at=CURRENT_TIMESTAMP
WHERE status IN ('SELECTED','QUEUED','SENDING');
