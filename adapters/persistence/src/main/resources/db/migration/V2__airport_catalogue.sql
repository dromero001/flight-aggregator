-- The airports the system knows: the 32 of the mock network (its StandardCatalogue).
CREATE TABLE airport (
    code char(3) PRIMARY KEY
);

INSERT INTO airport (code) VALUES
    ('MAD'),
    ('BCN'),
    ('LHR'),
    ('CDG'),
    ('FRA'),
    ('AMS'),
    ('LIS'),
    ('FCO'),
    ('ZRH'),
    ('SVQ'),
    ('LPA'),
    ('PMI'),
    ('IBZ'),
    ('EDI'),
    ('MAN'),
    ('NCE'),
    ('BOD'),
    ('HAM'),
    ('BER'),
    ('EIN'),
    ('OPO'),
    ('FAO'),
    ('NAP'),
    ('VCE'),
    ('GVA'),
    ('JFK'),
    ('MIA'),
    ('BOG'),
    ('MEX'),
    ('GRU'),
    ('DXB'),
    ('NRT');
