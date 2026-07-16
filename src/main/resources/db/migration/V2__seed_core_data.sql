-- Seed: one tenant / team / project and a small price table for local dev + tests.

insert into tenant (id, name)
values ('00000000-0000-0000-0000-000000000001', 'acme');

insert into team (id, tenant_id, name)
values ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000001', 'platform');

insert into project (id, team_id, name)
values ('00000000-0000-0000-0000-000000000021', '00000000-0000-0000-0000-000000000011', 'chatbot');

insert into model_price (provider, model, input_price_per_1k, output_price_per_1k) values
    ('openai',    'gpt-4o',            0.002500, 0.010000),
    ('openai',    'gpt-4o-mini',       0.000150, 0.000600),
    ('anthropic', 'claude-sonnet-4-5', 0.003000, 0.015000),
    ('anthropic', 'claude-haiku-4-5',  0.001000, 0.005000),
    ('gemini',    'gemini-2.5-pro',    0.001250, 0.010000),
    ('gemini',    'gemini-2.5-flash',  0.000300, 0.002500);
