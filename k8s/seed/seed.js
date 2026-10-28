// mongosh seed for the lab. Safe to run again: it wipes and recreates the three collections, with dates relative to "today"
// (Brazil, UTC-3, no daylight saving time since 2019).
//
// Collections (database reportsdb):
//   report_catalog      base features of each customer's reports                  (read by reports-service)
//   customer_selections features inserted from a campaign offer = the 2nd cart     (written by reports-gateway POST)
//   campaign_offers     recommendations with exhibition dates = the 1st cart       (read by campaign-offers-service)

const dbx = db.getSiblingDB('reportsdb');
const N = parseInt((typeof process !== 'undefined' && process.env && process.env.CUSTOMERS) || '50', 10);

function isoDay(offsetDays) {
  const ms = Date.now() - 3 * 3600 * 1000 + offsetDays * 86400000;
  return new Date(ms).toISOString().slice(0, 10);
}

// Valid CNPJ: 8 digits + branch 0001 + 2 check digits.
function cnpj(n) {
  const base = String(10000000 + n).split('').map(Number).concat([0, 0, 0, 1]);
  const dv = (digits, weights) => {
    const sum = digits.reduce((acc, d, i) => acc + d * weights[i], 0);
    const r = sum % 11;
    return r < 2 ? 0 : 11 - r;
  };
  const d1 = dv(base, [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]);
  const d2 = dv(base.concat([d1]), [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]);
  return base.concat([d1, d2]).join('');
}

const AVANCADO = 'RELATORIO_AVANCADO_PJ';
const BASICO = 'RELATORIO_BASICO_PJ';

dbx.report_catalog.deleteMany({});
dbx.customer_selections.deleteMany({});
dbx.campaign_offers.deleteMany({});

const catalog = [];
const selections = [];
const offers = [];

for (let i = 1; i <= N; i++) {
  const logon = 'user' + String(i).padStart(3, '0');
  const document = cnpj(i);
  const id = logon + '|' + document;

  catalog.push({
    _id: id, logon, document,
    reports: [
      { reportName: AVANCADO, features: [
        { featureTransaction: 'QSCO', featureName: 'Quadro coparticipação' },
        { featureTransaction: 'FINC', featureName: 'Dados financeiros' } ] },
      { reportName: BASICO, features: [
        { featureTransaction: 'CADA', featureName: 'Dados cadastrais' } ] }
    ]
  });

  const offerA = new ObjectId().toHexString();   // valid today        (the one the tests click on)
  const offerB = new ObjectId().toHexString();   // valid today, last day is today
  const offerC = new ObjectId().toHexString();   // expired yesterday
  const offerD = new ObjectId().toHexString();   // starts in 3 days

  offers.push(
    { _id: offerA, logon, document, reportName: AVANCADO, exhibitionStartDate: isoDay(-2), exhibitionEndDate: isoDay(5),
      features: [{ featureTransaction: 'QSCN', featureName: 'Quadro societário' }] },
    { _id: offerB, logon, document, reportName: AVANCADO, exhibitionStartDate: isoDay(-1), exhibitionEndDate: isoDay(0),
      features: [{ featureTransaction: 'SCRE', featureName: 'Score de crédito' }] },
    { _id: offerC, logon, document, reportName: AVANCADO, exhibitionStartDate: isoDay(-10), exhibitionEndDate: isoDay(-1),
      features: [{ featureTransaction: 'PROT', featureName: 'Protestos' }] },
    { _id: offerD, logon, document, reportName: BASICO, exhibitionStartDate: isoDay(3), exhibitionEndDate: isoDay(10),
      features: [{ featureTransaction: 'RELA', featureName: 'Relacionamentos' }] }
  );

  // Odd users already hold the EXPIRED offer in their cart (preSelected=true): it must stay visible until they remove it.
  // Even users do not: for them the expired offer is simply not shown anywhere.
  const baseline = (i % 2 === 1)
    ? [{ campaignOfferId: offerC, reportName: AVANCADO, featureTransaction: 'PROT', featureName: 'Protestos',
         insertedAt: new Date().toISOString() }]
    : [];
  selections.push({ _id: id, logon, document, version: NumberLong(0), selections: baseline, seedSelections: baseline });
}

dbx.report_catalog.insertMany(catalog);
dbx.customer_selections.insertMany(selections);
dbx.campaign_offers.insertMany(offers);
dbx.campaign_offers.createIndex({ logon: 1, document: 1 });
dbx.report_catalog.createIndex({ logon: 1 });

print('seeded ' + N + ' customers: ' + catalog[0].logon + ' (' + catalog[0].document + ') ... ' +
      catalog[N - 1].logon + ' (' + catalog[N - 1].document + ')');
print('offer dates: A valid ' + isoDay(-2) + '..' + isoDay(5) + ' | B ends today ' + isoDay(0) + ' | C expired ' + isoDay(-1) + ' | D starts ' + isoDay(3));
