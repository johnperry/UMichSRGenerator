/*---------------------------------------------------------------
*  Copyright 2015 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
*----------------------------------------------------------------*/

package edu.umich.ctp;

import java.io.File;
import java.util.Iterator;
import org.apache.log4j.Logger;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.pipeline.AbstractPipelineStage;
import org.rsna.ctp.pipeline.Processor;
import org.rsna.ctp.stdstages.Scriptable;
import org.rsna.ctp.stdstages.anonymizer.AnonymizerFunctions;
import org.rsna.util.FileUtil;
import org.w3c.dom.Element;

import org.dcm4che.data.Dataset;
import org.dcm4che.data.DcmElement;
import org.dcm4che.data.DcmObjectFactory;
import org.dcm4che.data.FileMetaInfo;
import org.dcm4che.data.SpecificCharacterSet;
import org.dcm4che.dict.DictionaryFactory;
import org.dcm4che.dict.TagDictionary;
import org.dcm4che.dict.Tags;
import org.dcm4che.dict.UIDs;
import org.dcm4che.dict.VRs;

/**
 * The UMichSRGenerator pipeline stage class.
 */
public class UMichSRGenerator extends AbstractPipelineStage implements Processor, Scriptable {

	static final Logger logger = Logger.getLogger(UMichSRGenerator.class);
	static final DictionaryFactory dFact = DictionaryFactory.getInstance();
	static final TagDictionary tagDictionary = dFact.getDefaultTagDictionary();
    static final DcmObjectFactory objectFactory = DcmObjectFactory.getInstance();

	File dicomScriptFile = null; //the DicomFilter script that determines whether to process the object
	String prefix = "";

	/**
	 * Construct the DicomCorrector PipelineStage.
	 * @param element the XML element from the configuration file
	 * specifying the configuration of the stage.
	 */
	public UMichSRGenerator(Element element) {
		super(element);
		dicomScriptFile = getFilterScriptFile(element.getAttribute("dicomScript"));
		prefix = element.getAttribute("prefix").trim();
		if (prefix.equals("")) prefix = "9999";
	}

	//Implement the Scriptable interface
	/**
	 * Get the script files.
	 * @return the script files used by this stage.
	 */
	public File[] getScriptFiles() {
		return new File[] { dicomScriptFile };
	}

	/**
	 * Convert a image DicomObject into an SR and return the SR.
	 * If the object is not a DicomObject or not an image, pass the object unmodified.
	 * @param fileObject the object to process.
	 * @return the processed FileObject.
	 */
	public FileObject process(FileObject fileObject) {
		lastFileIn = new File(fileObject.getFile().getAbsolutePath());
		lastTimeIn = System.currentTimeMillis();

		if (fileObject instanceof DicomObject) {
			DicomObject dob = (DicomObject)fileObject;

			//If there is a dicomScriptFile, use it to determine whether to anonymize
			if (dob.isImage() && dob.matches(dicomScriptFile)) {

				//Okay, create the SR object
				dob = makeSR(dob);
				
				//Pass it on
				if (dob != null) fileObject = dob;
			}
		}

		lastFileOut = new File(fileObject.getFile().getAbsolutePath());
		lastTimeOut = System.currentTimeMillis();
		return fileObject;
	}

    private DicomObject makeSR(DicomObject dob) {
		try {
			//dob is the input dataset
			Dataset dobDS = dob.getDataset();
			SpecificCharacterSet scs = dobDS.getSpecificCharacterSet();
			
			//sr will be the output dataset
			DicomObject sr = new DicomObject(dob.getFile(), true);
			Dataset srDS = sr.getDataset();
			
			for (Iterator it=dobDS.iterator(); it.hasNext(); ) {
				DcmElement el = (DcmElement)it.next();
				int tag = el.tag();
				int group = (tag >> 16) & 0xFFFF;
				int element = tag & 0xFFFF;
				
				if ( ((group & 1) != 0)
						|| ((group > 0x20) && (group < 0x32)) 
							|| ((group == 0x20) && (element > 0x13)) 
								|| (group > 0x40) ) {
					try { srDS.remove(tag); }
					catch (Exception ignore) { logger.debug("Unable to remove "+tag+" from dataset."); }
				}
			}
			
			//Force the TransferSyntax 
			String transferSyntaxUID = UIDs.ExplicitVRLittleEndian;
			srDS.getFileMetaInfo().putUI(Tags.TransferSyntaxUID, transferSyntaxUID);

			//Set the SOPClass
			String sopClassUID = UIDs.BasicTextSR;
			srDS.putUI( Tags.SOPClassUID, sopClassUID);
			
			//Create a new SOPInstanceUID
			String sopInstanceID = AnonymizerFunctions.newUID(prefix);
			srDS.putUI(Tags.SOPInstanceUID, sopInstanceID);
				
			//Set the Modality to SR
			srDS.putCS(Tags.Modality, "SR");
			
			//Put in an SQ element for a document title
			DcmElement el = srDS.putSQ(Tags.ConceptNameCodeSeq);
			Dataset itemDS = objectFactory.newDataset();
			el.addItem(itemDS);
			String modality = dobDS.getString(Tags.Modality);
			if (modality == null) ;
			else if (modality.equals("CT")) {
				itemDS.putSH(Tags.CodeValue, "18747-6");
				itemDS.putSH(Tags.CodingSchemeDesignator, "LN");
				itemDS.putSH(Tags.CodeMeaning, "CT Report");
			}
			else if (modality.equals("MR")) {
				itemDS.putSH(Tags.CodeValue, "18755-9");
				itemDS.putSH(Tags.CodingSchemeDesignator, "LN");
				itemDS.putSH(Tags.CodeMeaning, "MRI Report");
			}
				
			//Put in an SQ element for patient characteristics
			DcmElement pc = srDS.putSQ(Tags.ContentSeq);
			Dataset pcDS = objectFactory.newDataset();
			pc.addItem(pcDS);
				pcDS.putCS(Tags.RelationshipType, "CONTAINS");
				pcDS.putCS(Tags.ValueType, "CONTAINER");
				DcmElement x = pcDS.putSQ(Tags.ConceptNameCodeSeq);
				Dataset xDS = objectFactory.newDataset();
				x.addItem(xDS);
					xDS.putSH(Tags.CodeValue, "121118");
					xDS.putSH(Tags.CodingSchemeDesignator, "DCM");
					xDS.putLO(Tags.CodeMeaning, "Patient Characteristics");
				pcDS.putCS(Tags.ContinuityOfContent, "SEPARATE");
				
				DcmElement cs = pcDS.putSQ(Tags.ContentSeq);
				
				addContentSeqItem(cs, "TEXT", "121009", "DCM", "Institution Name", Tags.TextValue, dobDS.getString(Tags.InstitutionName));

				addContentSeqItem(cs, "TEXT", "121029", "DCM", "Subject Name", Tags.TextValue, dobDS.getString(Tags.PatientName));
				
				addContentSeqItem(cs, "TEXT", "121030", "DCM", "Subject ID", Tags.TextValue, dobDS.getString(Tags.PatientID));
				
				String sd = dobDS.getString(Tags.StudyDate);
				sd = sd.substring(4,6)+"/"+sd.substring(6,8)+"/"+sd.substring(0,4);
				addContentSeqItem(cs, "TEXT", "111060", "DCM", "Study Date", Tags.TextValue, sd);
				
				addContentSeqItem(cs, "TEXT", "121065", "DCM", "Procedure Description", Tags.TextValue, dobDS.getString(Tags.StudyDescription));

				String contrast = dobDS.getString(Tags.ContrastBolusAgent);
				if ((contrast != null) && !contrast.trim().equals("")) {
					addContentSeqItem(cs, "TEXT", "122086", "DCM", "Contrast Administered", Tags.TextValue, contrast);
				}

				String field = dobDS.getString(Tags.MagneticFieldStrength);
				if ((field != null) && !field.trim().equals("")) {
					//note: can't find any DCM code for magnetic field strength
					addContentSeqItem(cs, "TEXT", "", "", "Magnetic Field Strength", Tags.TextValue, dobDS.getString(Tags.MagneticFieldStrength));
				}

				String diameter = dobDS.getString(Tags.ReconstructionDiameter);
				if ((diameter != null) && !diameter.trim().equals("")) {
					double diam = Float.parseFloat(diameter.trim()) / 10.0;
					String d = String.format("%.1f cm", diam);
					//note: using DCM code for Reconstruction Algorithm
					addContentSeqItem(cs, "TEXT", "113961", "DCM", "Reconstruction Diameter", Tags.TextValue, d);
				}

			//Write the dataset to a file in the root directory
			File file = new File(root, sopInstanceID + ".dcm");
			sr.saveAs(file, false, true); //skip the pixels
			sr.close();
			return new DicomObject(file);
		}
		catch (Exception unable) {
			logger.debug(unable.getMessage(), unable);
			return null;
		}
	}
	
	private void addContentSeqItem(
					DcmElement contentSeqElement,
					String valueType,
					String codeValue,
					String codingSchemeDesignator,
					String codeMeaning,
					int valueTag,
					String value) {
						
		Dataset csDS = objectFactory.newDataset();
		contentSeqElement.addItem(csDS);
			csDS.putCS(Tags.RelationshipType, "CONTAINS");
			csDS.putCS(Tags.ValueType, valueType);
			DcmElement conceptNameCodeSeq = csDS.putSQ(Tags.ConceptNameCodeSeq);
			Dataset cncsDS = objectFactory.newDataset();
			conceptNameCodeSeq.addItem(cncsDS);
				if (!codeValue.equals("")) {
					cncsDS.putSH(Tags.CodeValue, codeValue);
					cncsDS.putSH(Tags.CodingSchemeDesignator, codingSchemeDesignator);
				}
				cncsDS.putLO(Tags.CodeMeaning, codeMeaning);
			csDS.putXX(valueTag, value);
	}			
		
}